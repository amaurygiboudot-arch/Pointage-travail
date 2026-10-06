"use strict";

const { createHash } = require("node:crypto");
const { SERVICES, validateServiceOrder } = require("./billingServiceCatalog");
const digest = value => createHash("sha256").update(value).digest("hex");
function createReportBilling({ db, records, user, livePurchases, play, now, BillingError, documentKey }) {
  const fail = (code, message, details) => { const error = new BillingError(code, message); error.details = details; throw error; };
  const reports = uid => user(uid).collection("serviceReports");
  const documents = uid => user(uid).collection("serviceDocuments");
  async function prepare(uid, data) {
    let order;
    try { order = validateServiceOrder(data); } catch (e) { fail(e.code, e.message); }
    const documentSha256 = documentKey(data.documentSha256);
    const reportId = digest(uid + "\n" + order.productId + "\n" + order.inputSha256);
    const ref = reports(uid).doc(reportId), marker = documents(uid).doc(documentSha256);
    const command = user(uid).collection("serviceCommands").doc(digest(order.requestId));
    await db.runTransaction(async tx => {
      const previous = await tx.get(ref), marked = await tx.get(marker), requested = await tx.get(command);
      if (requested.exists && requested.data().reportId !== reportId) fail("already-exists", "Identifiant de commande déjà utilisé pour d'autres données.");
      if (previous.exists && (previous.data().documentSha256 !== documentSha256 || previous.data().inputSha256 !== order.inputSha256)) fail("already-exists", "Cette commande est déjà liée à un autre rapport.");
      if (marked.exists && marked.data().reportId !== reportId) fail("already-exists", "Document déjà lié à une autre commande.");
      if (!previous.exists) tx.create(ref, { ...order, documentSha256, uid, preparedAtMs: now(), status: "prepared" });
      if (!marked.exists) tx.create(marker, { reportId, productId: order.productId });
      if (!requested.exists) tx.create(command, { reportId });
    });
    return { reportId, prepared: true, documentSha256, productId: order.productId };
  }
  async function getPrepared(uid, data) {
    if (typeof data?.reportId !== "string" || !/^[a-f0-9]{64}$/.test(data.reportId)) fail("invalid-argument", "Identifiant de rapport requis.");
    const doc = await reports(uid).doc(data.reportId).get();
    if (!doc.exists || doc.data().uid !== uid || doc.data().documentSha256 !== documentKey(data.documentSha256)) fail("permission-denied", "Rapport préparé introuvable pour ce compte et ce document.");
    return { ...doc.data(), reportId: data.reportId };
  }
  async function validatePurchase(uid, purchase, tx = null) {
    const ref = reports(uid).doc(purchase.reportId);
    const snap = tx ? await tx.get(ref) : await ref.get();
    if (!snap.exists || snap.data().uid !== uid || snap.data().productId !== purchase.productId || snap.data().documentSha256 !== purchase.documentSha256) fail("permission-denied", "Paiement sans rapport prêt correspondant.");
    return snap.data();
  }
  function deliverPurchase(tx, uid, purchase, purchaseId) {
    tx.set(reports(uid).doc(purchase.reportId), { status: "delivered", purchaseId, funding: "single", deliveredAtMs: now() }, { merge: true });
  }
  async function validProof(uid, report) {
    if (report.funding === "owner") return false; // Owner status is always re-read by callable.
    if (!report.purchaseId) return false;
    const record = await records.doc(report.purchaseId).get();
    if (!record.exists || record.data().uid !== uid) return false;
    const purchase = record.data();
    if (report.funding === "plus") {
      if (!report.orderId) return false;
      try {
        const order = await play.order(report.orderId);
        return order.state === "PROCESSED" && order.purchaseToken === purchase.token;
      } catch (error) { if (error instanceof BillingError && error.code === "permission-denied") return false; throw error; }
    }
    const active = await livePurchases(uid, (p, id) => id === report.purchaseId);
    if (report.funding === "legacy") return active.some(p => p.kind === "analysis" && p.analysisReportId === report.reportId && p.analysisUsed === true);
    return active.some(p => p.productId === report.productId && p.documentSha256 === report.documentSha256 && p.reportId === report.reportId);
  }
  const fundingVersion = report => JSON.stringify([report.funding || null, report.purchaseId || null, report.orderId || null, report.deliveredAtMs || null]);
  function checkFundingVersion(current, observed) {
    if (fundingVersion(current) !== fundingVersion(observed)) fail("aborted", "Financement du rapport modifié : nouvelle vérification nécessaire.", { retryFunding: true });
  }
  async function authorize(uid, data, owner = false, allowPlus = data?.usePlusCredit === true || data?.useAnalysisCredit === true, attempt = 0) {
    try { return await authorizeOnce(uid, data, owner, allowPlus); }
    catch (error) {
      if (error instanceof BillingError && error.code === "aborted" && error.details?.retryFunding === true && attempt < 2) {
        return authorize(uid, data, owner, allowPlus, attempt + 1);
      }
      throw error;
    }
  }
  async function authorizeOnce(uid, data, owner, allowPlus) {
    const report = await getPrepared(uid, data);
    if (data?.usePlusCredit === true && data?.useAnalysisCredit === true) fail("invalid-argument", "Choisis une seule source de financement du bulletin.");
    const response = { authorized: true, documentSha256: report.documentSha256, reportId: report.reportId, productId: report.productId };
    if (owner) return response;
    if (await validProof(uid, report)) return response;
    // Re-purchased reports bind their new receipt to the same immutable prepared manifest.
    const singles = await livePurchases(uid, p => p.productId === report.productId && p.reportId === report.reportId && p.documentSha256 === report.documentSha256);
    if (singles.length) {
      await reports(uid).doc(report.reportId).set({ status: "delivered", purchaseId: singles[0].id, funding: "single", deliveredAtMs: now() }, { merge: true });
      return response;
    }
    if (report.productId !== "horatrack_analysis") fail("permission-denied", "Cette prestation nécessite son propre achat vérifié.", { productId: report.productId, reportId: report.reportId });
    if (!allowPlus) fail("permission-denied", "Confirme l'utilisation du crédit Plus avant d'accéder à ce rapport.", { productId: report.productId, reportId: report.reportId });
    const legacy = data?.useAnalysisCredit === true
      ? (await livePurchases(uid, p => p.kind === "analysis" && !p.reportId && p.documentSha256 == null && !p.analysisUsed))[0] : null;
    if (legacy) {
      const used = await db.runTransaction(async tx => {
        const current = await tx.get(reports(uid).doc(report.reportId)), credit = await tx.get(records.doc(legacy.id));
        if (!current.exists) fail("permission-denied", "Rapport préparé absent.");
        checkFundingVersion(current.data(), report);
        if (current.data()?.funding === "single" || (current.data()?.funding === "legacy" && current.data()?.purchaseId === legacy.id)) return true;
        if (!credit.exists || credit.data().uid !== uid || credit.data().analysisUsed) return false;
        tx.update(records.doc(legacy.id), { analysisUsed: true, analysisReportId: report.reportId });
        tx.set(reports(uid).doc(report.reportId), { status: "delivered", funding: "legacy", purchaseId: legacy.id, deliveredAtMs: now() }, { merge: true });
        return true;
      });
      if (used) return response;
    }
    if (data?.usePlusCredit !== true) fail("permission-denied", "Aucun crédit bulletin ponctuel disponible.");
    const plus = (await livePurchases(uid, p => p.kind === "plus")).find(p => p.orderId);
    if (!plus) fail("permission-denied", "Analyse de bulletin : achat ou crédit Plus requis.");
    const order = await play.order(plus.orderId);
    if (order.state !== "PROCESSED" || order.purchaseToken !== plus.token) fail("permission-denied", "Paiement Plus non vérifié.");
    const period = new Date(now()).toISOString().slice(0, 7);
    const quota = user(uid).collection("analysisPeriods").doc(period);
    await db.runTransaction(async tx => {
      const current = await tx.get(reports(uid).doc(report.reportId)), used = await tx.get(quota), purchase = await tx.get(records.doc(plus.id));
      if (!current.exists || current.data().documentSha256 !== report.documentSha256) fail("permission-denied", "Rapport modifié.");
      checkFundingVersion(current.data(), report);
      if (current.data().funding === "single") return;
      if (current.data().funding === "plus" && current.data().purchaseId === plus.id && current.data().orderId === plus.orderId) return;
      if (used.exists && used.data().reportId !== report.reportId) fail("resource-exhausted", "Le crédit bulletin Plus de ce mois est déjà utilisé.");
      if (!purchase.exists || purchase.data().uid !== uid || purchase.data().supersededBy) fail("permission-denied", "Abonnement Plus remplacé.");
      if (!used.exists) tx.create(quota, { reportId: report.reportId, productId: report.productId, reservedAtMs: now() });
      tx.set(reports(uid).doc(report.reportId), { status: "delivered", funding: "plus", purchaseId: plus.id, orderId: plus.orderId, deliveredAtMs: now(), period }, { merge: true });
    });
    return response;
  }
  async function documentMarker(uid, hash) {
    const doc = await documents(uid).doc(hash).get();
    return doc.exists ? doc.data() : null;
  }
  return { prepare, authorize, getPrepared, validatePurchase, deliverPurchase, documentMarker };
}
module.exports = { createReportBilling };
