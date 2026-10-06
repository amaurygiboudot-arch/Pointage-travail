"use strict";

const { createHash } = require("node:crypto");
const { SERVICES, validateServiceOrder } = require("./billingServiceCatalog");
const PACKAGE = "com.amaury.pointage";
const PRODUCTS = Object.freeze({ horatrack_premium: "premium", horatrack_plus: "plus", horatrack_analysis: "analysis", horatrack_pdf: "pdf" });
const sha = value => createHash("sha256").update(value).digest("hex");
class BillingError extends Error { constructor(code, message) { super(message); this.code = code; } }
const deny = message => { throw new BillingError("permission-denied", message); };
function documentKey(value) {
  if (typeof value !== "string" || !/^[a-f0-9]{64}$/.test(value)) throw new BillingError("invalid-argument", "Document SHA256 requis.");
  return value;
}
function purchaseRequest(data) {
  if (data && Object.hasOwn(SERVICES, data.productId) && data.productId !== "horatrack_analysis") {
    throw new BillingError("failed-precondition", "Service non disponible : aucun achat autorisé.");
  }
  if (!data || !Object.hasOwn(PRODUCTS, data.productId) || typeof data.purchaseToken !== "string" || !data.purchaseToken.length || data.purchaseToken.length > 4096) {
    throw new BillingError("invalid-argument", "Achat non reconnu.");
  }
  return { productId: data.productId, token: data.purchaseToken, kind: PRODUCTS[data.productId], documentSha256: data.productId === "horatrack_pdf" ? documentKey(data.documentSha256) : null };
}
function verifyState(raw, purchase, uid, now = Date.now()) {
  if (purchase.kind === "premium" || purchase.kind === "plus") {
    if (raw.externalAccountIdentifiers?.obfuscatedExternalAccountId !== sha(uid)) deny("Achat associé à un autre compte.");
    if (!["SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD", "SUBSCRIPTION_STATE_CANCELED"].includes(raw.subscriptionState)) deny("Abonnement inactif.");
    const line = raw.lineItems?.find(item => item.productId === purchase.productId && Date.parse(item.expiryTime) > now);
    if (!line) deny("Abonnement expiré ou produit différent.");
    return { expiryMs: Date.parse(line.expiryTime), orderId: line.latestSuccessfulOrderId || raw.latestOrderId || null, acknowledged: raw.acknowledgementState === "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED", linkedToken: raw.linkedPurchaseToken || null };
  }
  if (raw.obfuscatedExternalAccountId !== sha(uid)) deny("Achat associé à un autre compte.");
  if (purchase.kind === "pdf" && raw.obfuscatedExternalProfileId !== purchase.documentSha256) deny("Paiement associé à un autre document.");
  if (raw.purchaseState !== 0) deny("Paiement non finalisé ou remboursé.");
  return { expiryMs: null, acknowledged: raw.acknowledgementState === 1, consumed: raw.consumptionState === 1 };
}
function createPlayClient({ credential, fetchImpl = fetch }) {
  const root = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PACKAGE}/purchases`;
  async function request(path, body) {
    const access = await credential.getAccessToken();
    const response = await fetchImpl(new URL(root + path).href, { method: body === undefined ? "GET" : "POST", headers: { Authorization: `Bearer ${access.access_token}`, "Content-Type": "application/json" }, ...(body !== undefined ? { body: JSON.stringify(body) } : {}), signal: AbortSignal.timeout(15000) });
    if (!response.ok) throw new BillingError([404, 410].includes(response.status) ? "permission-denied" : "unavailable", "Vérification Google Play indisponible.");
    return response.status === 204 ? {} : response.json();
  }
  const path = p => `/products/${encodeURIComponent(p.productId)}/tokens/${encodeURIComponent(p.token)}`;
  return {
    order: id => request(`/../orders/${encodeURIComponent(id)}`),
    get: p => ["premium", "plus"].includes(p.kind) ? request(`/subscriptionsv2/tokens/${encodeURIComponent(p.token)}`) : request(path(p)),
    settle: (p, state) => ["premium", "plus"].includes(p.kind)
      ? state.acknowledged ? Promise.resolve() : request(`/subscriptions/${encodeURIComponent(p.productId)}/tokens/${encodeURIComponent(p.token)}:acknowledge`, {})
      : state.consumed ? Promise.resolve() : request(path(p) + ":consume", {}),
  };
}
function createBillingService({ db, play, now = Date.now }) {
  const records = db.collection("billingPurchasesV1");
  const user = uid => db.collection("billingUsersV1").doc(uid);
  async function verify(uid, data) {
    const p = purchaseRequest(data);
    const state = verifyState(await play.get(p), p, uid, now());
    const ref = records.doc(sha(p.token));
    await db.runTransaction(async tx => {
      const existing = await tx.get(ref);
      let linkedRef = null;
      if (state.linkedToken) {
        linkedRef = records.doc(sha(state.linkedToken));
        const linked = await tx.get(linkedRef);
        if (linked.exists && linked.data().uid !== uid) deny("Abonnement remplacé associé à un autre compte.");
      }
      if (existing.exists) {
        const old = existing.data();
        if (old.supersededBy) deny("Abonnement remplacé : ancien jeton désactivé.");
        if (old.uid !== uid || old.productId !== p.productId || old.documentSha256 !== p.documentSha256) deny("Jeton déjà associé à un autre achat.");
      } else {
        if (state.consumed) deny("Achat déjà consommé sans preuve serveur.");
        tx.create(ref, { uid, productId: p.productId, kind: p.kind, token: p.token, documentSha256: p.documentSha256, createdAtMs: now(), analysisUsed: false });
      }
      if (linkedRef) tx.set(linkedRef, { uid, supersededBy: ref.id || sha(p.token) }, { merge: true });
      tx.set(ref, { expiryMs: state.expiryMs, orderId: state.orderId || null, verifiedAtMs: now(), active: true }, { merge: true });
    });
    // Settlement after durable claim: retrying the same token never creates a second credit.
    await play.settle(p, state);
    return entitlements(uid);
  }
  async function livePurchases(uid, relevant = () => true) {
    const snapshot = await records.where("uid", "==", uid).get();
    const active = [];
    for (const doc of snapshot.docs) {
      const p = doc.data();
      if (p.supersededBy || !relevant(p, doc.id)) continue;
      try {
        const state = verifyState(await play.get(p), p, uid, now());
        active.push({ ...p, id: doc.id, expiryMs: state.expiryMs, orderId: state.orderId || p.orderId });
      } catch (error) {
        if (error instanceof BillingError && error.code === "permission-denied") {
          await doc.ref.set({ active: false, verifiedAtMs: now() }, { merge: true });
        } else throw error; // Fail closed on network/API failure, never trust cached entitlement.
      }
    }
    return active;
  }
  async function entitlements(uid, owner = false) {
    if (owner) return { owner: true, premium: true, plus: true, analysisCredits: 1, pdfIds: [], obfuscatedAccountId: sha(uid) };
    const active = await livePurchases(uid);
    const plus = active.some(p => p.kind === "plus");
    const period = new Date(now()).toISOString().slice(0, 7);
    const usage = await user(uid).collection("analysisPeriods").doc(period).get();
    return { owner: false, premium: plus || active.some(p => p.kind === "premium"), plus,
      analysisCredits: active.filter(p => p.kind === "analysis" && !p.analysisUsed).length + (plus && !usage.exists ? 1 : 0),
      pdfIds: active.filter(p => p.kind === "pdf").map(p => p.documentSha256), obfuscatedAccountId: sha(uid) };
  }
  async function authorizePdf(uid, data, owner = false) {
    const key = documentKey(data?.documentSha256);
    if (owner) return { authorized: true, documentSha256: key };
    const grant = user(uid).collection("ownedPdfs").doc(key);
    const previous = await grant.get();
    if (previous.exists) {
      const proof = previous.data();
      const purchase = await records.doc(proof.purchaseId).get();
      if (purchase.exists && purchase.data().uid === uid && proof.orderId) {
        try {
          const order = await play.order(proof.orderId);
          if (order.state === "PROCESSED" && order.purchaseToken === purchase.data().token) return { authorized: true, documentSha256: key };
        } catch (error) {
          if (!(error instanceof BillingError) || error.code !== "permission-denied") throw error;
        }
      }
    }
    const analysis = await user(uid).collection("completedAnalyses").doc(key).get();
    const active = await livePurchases(uid, (p, id) => ["premium", "plus"].includes(p.kind) ||
      (p.kind === "pdf" && p.documentSha256 === key) || (analysis.exists && id === analysis.data().purchaseId));
    const included = active.find(p => ["premium", "plus"].includes(p.kind));
    if (included) {
      if (!included.orderId) throw new BillingError("unavailable", "Preuve de commande Google Play manquante.");
      const order = await play.order(included.orderId);
      if (order.state !== "PROCESSED" || order.purchaseToken !== included.token) deny("Commande non finalisée.");
      await db.runTransaction(async tx => {
        tx.set(grant, { documentSha256: key, purchaseId: included.id, orderId: included.orderId, grantedAtMs: now() });
      });
      return { authorized: true, documentSha256: key };
    }
    if (active.some(p => p.kind === "pdf" && p.documentSha256 === key)) return { authorized: true, documentSha256: key };
    // Only a server-created completed analysis may include its own PDF.
    if (analysis.exists && analysis.data().documentSha256 === key) {
      const tokenId = analysis.data().purchaseId;
      if (active.some(p => p.id === tokenId && p.kind === "analysis")) return { authorized: true, documentSha256: key };
    }
    deny("Paiement vérifié requis avant de générer ou prévisualiser ce PDF.");
  }
  async function reserveAnalysis(uid, data, owner = false) {
    // Keep legacy purchased bulletin credits readable, but never spend them for a missing service.
    try {
      const order = validateServiceOrder({ ...data, productId: data?.productId || "horatrack_analysis" });
      if (order.productId !== "horatrack_analysis") throw new BillingError("failed-precondition", "Un crédit bulletin ne donne pas accès à un autre service.");
    } catch (error) { throw new BillingError(error.code || "invalid-argument", error.message); }
    const requestId = data?.requestId;
    if (typeof requestId !== "string" || !/^[a-zA-Z0-9_-]{16,128}$/.test(requestId)) throw new BillingError("invalid-argument", "Identifiant idempotent requis.");
    const active = owner ? [] : await livePurchases(uid);
    const period = new Date(now()).toISOString().slice(0, 7);
    const reservation = user(uid).collection("analysisReservations").doc(requestId);
    const monthly = user(uid).collection("analysisPeriods").doc(period);
    return db.runTransaction(async tx => {
      const previous = await tx.get(reservation);
      if (previous.exists) return { reservationId: requestId, reserved: true };
      const month = await tx.get(monthly);
      let purchaseId = null;
      if (!owner && !(active.some(p => p.kind === "plus") && !month.exists)) {
        for (const p of active.filter(p => p.kind === "analysis")) {
          const current = await tx.get(records.doc(p.id));
          if (!current.data()?.analysisUsed) { purchaseId = p.id; break; }
        }
        if (!purchaseId) deny("Crédit d'analyse requis.");
      }
      if (purchaseId) tx.update(records.doc(purchaseId), { analysisUsed: true, analysisRequestId: requestId });
      else if (!owner) tx.create(monthly, { requestId, reservedAtMs: now() });
      tx.create(reservation, { purchaseId, period: purchaseId || owner ? null : period, owner, reservedAtMs: now(), status: "reserved" });
      return { reservationId: requestId, reserved: true };
    });
  }
  return { verify, entitlements, authorizePdf, reserveAnalysis };
}
module.exports = { PACKAGE, PRODUCTS, sha, BillingError, documentKey, purchaseRequest, verifyState, createPlayClient, createBillingService };
