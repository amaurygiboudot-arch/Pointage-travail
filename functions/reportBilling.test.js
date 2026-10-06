"use strict";
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { sha, createBillingService, BillingError } = require("./playBilling");
const { SERVICES } = require("./billingServiceCatalog");
const { memoryDb } = require("./billingTestFixtures");
const UID = "report-user";
function setup() {
  const db = memoryDb(); const receipts = new Map(); const orders = new Map(); let clock = Date.parse("2026-10-06");
  let calls = 0;
  const play = {
    get: async p => { calls++; const value = receipts.get(p.token); if (!value) throw new BillingError("permission-denied", "missing"); return value; },
    order: async id => { const value = orders.get(id); if (value instanceof Error) throw value; if (!value) throw new BillingError("permission-denied", "missing"); return value; },
    settle: async p => { const raw = receipts.get(p.token); if (raw.purchaseState === 0) raw.consumptionState = 1; },
  };
  const service = createBillingService({ db, play, now: () => clock });
  async function prepare(productId, label = productId, requestId = "request_" + sha(label)) {
    return service.prepareReport(UID, { productId, documentSha256: sha("pdf:" + label), inputSha256: sha("input:" + label), requestId });
  }
  function single(token, productId, hash) { receipts.set(token, { purchaseState: 0, consumptionState: 0, acknowledgementState: 0, obfuscatedExternalAccountId: sha(UID), obfuscatedExternalProfileId: hash }); }
  async function subscription(kind = "plus", token = kind) {
    receipts.set(token, { subscriptionState: "SUBSCRIPTION_STATE_ACTIVE", externalAccountIdentifiers: { obfuscatedExternalAccountId: sha(UID) }, acknowledgementState: "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED", lineItems: [{ productId: "horatrack_" + kind, expiryTime: "2026-11-01T00:00:00Z", latestSuccessfulOrderId: "GPA." + token }] });
    orders.set("GPA." + token, { state: "PROCESSED", purchaseToken: token });
    await service.verify(UID, { productId: "horatrack_" + kind, purchaseToken: token });
  }
  return { db, receipts, orders, service, prepare, single, subscription, calls: () => calls, clock: time => { clock = Date.parse(time); } };
}
test("prepared report identity is immutable across retries and devices", async () => {
  const f = setup(); const first = await f.prepare("horatrack_analysis", "one");
  const restored = await f.prepare("horatrack_analysis", "one", "different_request_id_123");
  assert.equal(restored.reportId, first.reportId);
  await assert.rejects(f.service.prepareReport(UID, { productId: "horatrack_analysis", requestId: "request_" + sha("one"), inputSha256: sha("different"), documentSha256: sha("differentPDF") }), e => e.code === "already-exists");
  await assert.rejects(f.service.prepareReport(UID, { productId: "horatrack_analysis", requestId: "other_request_123", inputSha256: sha("input:one"), documentSha256: sha("changedPDF") }), e => e.code === "already-exists");
});
test("four receipts bind prepared report and own PDF only, retries never add credit", async () => {
  for (const productId of Object.keys(SERVICES)) {
    const f = setup(); const report = await f.prepare(productId); f.single("token", productId, report.documentSha256);
    await assert.rejects(f.service.authorizeReport(UID, report), e => e.code === "permission-denied");
    await f.service.verify(UID, { productId, purchaseToken: "token", documentSha256: report.documentSha256 }); // Restore without local reportId.
    await f.service.verify(UID, { productId, purchaseToken: "token", documentSha256: report.documentSha256, reportId: report.reportId });
    assert.equal((await f.service.authorizeReport(UID, report)).authorized, true);
    assert.equal((await f.service.authorizePdf(UID, { documentSha256: report.documentSha256 })).authorized, true);
    assert.equal((await f.service.entitlements(UID)).analysisCredits, 0);
    await assert.rejects(f.service.authorizeReport("other-user", report));
    f.receipts.get("token").purchaseState = 1;
    await assert.rejects(f.service.authorizeReport(UID, report), e => e.code === "permission-denied");
  }
});
test("premium and ordinary PDF cannot finance own service", async () => {
  const f = setup(); await f.subscription("premium");
  const report = await f.prepare("horatrack_claim_dossier");
  await assert.rejects(f.service.authorizePdf(UID, { documentSha256: report.documentSha256 }), e => e.code === "permission-denied");
  f.single("wrong-product", "horatrack_pdf", report.documentSha256);
  const priorCalls = f.calls();
  await assert.rejects(f.service.verify(UID, { productId: "horatrack_pdf", purchaseToken: "wrong-product", documentSha256: report.documentSha256 }));
  assert.equal(f.calls(), priorCalls);
});
test("another user registering known hash cannot revoke ordinary paid PDF", async () => {
  const f = setup(); const hash = sha("known ordinary PDF");
  f.single("ordinary", "horatrack_pdf", hash);
  await f.service.verify(UID, { productId: "horatrack_pdf", purchaseToken: "ordinary", documentSha256: hash });
  await f.service.prepareReport("other-user", { productId: "horatrack_claim_dossier", documentSha256: hash, inputSha256: sha("other input"), requestId: "other_request_123" });
  assert.equal((await f.service.authorizePdf(UID, { documentSha256: hash })).authorized, true);
  await assert.rejects(f.service.authorizePdf("other-user", { documentSha256: hash }));
});
test("explicit Plus and legacy sources do not silently spend each other", async () => {
  const f = setup(); await f.subscription();
  const token = "legacy-separate";
  f.receipts.set(token, { purchaseState: 0, consumptionState: 1, obfuscatedExternalAccountId: sha(UID) });
  f.db.rows.set("billingPurchasesV1/" + sha(token), { uid: UID, productId: "horatrack_analysis", kind: "analysis", token, documentSha256: null, reportId: null, analysisUsed: false });
  const plusReport = await f.prepare("horatrack_analysis", "plus-only");
  await f.service.authorizeReport(UID, { ...plusReport, usePlusCredit: true });
  assert.equal(f.db.rows.get("billingPurchasesV1/" + sha(token)).analysisUsed, false);
  const legacyReport = await f.prepare("horatrack_analysis", "legacy-only");
  await f.service.authorizeReport(UID, { ...legacyReport, useAnalysisCredit: true });
  const rejected = await f.prepare("horatrack_analysis", "no-legacy");
  f.clock("2026-10-07");
  await assert.rejects(f.service.authorizeReport(UID, { ...rejected, useAnalysisCredit: true }));
  await assert.rejects(f.service.authorizeReport(UID, { ...rejected, useAnalysisCredit: true, usePlusCredit: true }), e => e.code === "invalid-argument");
  assert.equal([...f.db.rows.keys()].filter(k => k.includes("analysisPeriods")).length, 1);
  const fresh = setup(); await fresh.subscription(); const unfunded = await fresh.prepare("horatrack_analysis", "no-legacy-fresh-quota");
  await assert.rejects(fresh.service.authorizeReport(UID, { ...unfunded, useAnalysisCredit: true }));
  assert.equal((await fresh.service.entitlements(UID)).plusAnalysisCreditAvailable, true);
});
test("pending wrong hash and absent prepared report cannot deliver or consume", async () => {
  const f = setup(); const report = await f.prepare("horatrack_analysis");
  f.single("pending", "horatrack_analysis", report.documentSha256); f.receipts.get("pending").purchaseState = 2;
  await assert.rejects(f.service.verify(UID, { productId: "horatrack_analysis", purchaseToken: "pending", documentSha256: report.documentSha256 }));
  assert.equal(f.receipts.get("pending").consumptionState, 0);
  f.single("wrong-hash", "horatrack_analysis", sha("wrong"));
  await assert.rejects(f.service.verify(UID, { productId: "horatrack_analysis", purchaseToken: "wrong-hash", documentSha256: report.documentSha256 }));
  await assert.rejects(f.service.verify(UID, { productId: "horatrack_analysis", purchaseToken: "wrong-hash", documentSha256: sha("wrong") }));
});
test("Plus requires explicit use, monthly credit atomic across different reports and retry free", async () => {
  const f = setup(); await f.subscription(); const first = await f.prepare("horatrack_analysis", "first"), second = await f.prepare("horatrack_analysis", "second");
  await assert.rejects(f.service.authorizePdf(UID, { documentSha256: first.documentSha256 }));
  await assert.rejects(f.service.authorizeReport(UID, first));
  assert.equal((await f.service.entitlements(UID)).analysisCredits, 1);
  const results = await Promise.allSettled([first, second].map(r => f.service.authorizeReport(UID, { ...r, usePlusCredit: true })));
  assert.equal(results.filter(r => r.status === "fulfilled").length, 1);
  const winner = results[0].status === "fulfilled" ? first : second;
  await f.service.authorizeReport(UID, { ...winner, usePlusCredit: true });
  assert.equal((await f.service.entitlements(UID)).analysisCredits, 0);
  const dossier = await f.prepare("horatrack_claim_dossier");
  await assert.rejects(f.service.authorizeReport(UID, { ...dossier, usePlusCredit: true }), e => e.code === "permission-denied");
});
test("Plus report remains after expiration but financing order refund revokes", async () => {
  const f = setup(); await f.subscription(); const report = await f.prepare("horatrack_analysis");
  await f.service.authorizeReport(UID, { ...report, usePlusCredit: true });
  f.clock("2026-12-01"); f.receipts.get("plus").subscriptionState = "SUBSCRIPTION_STATE_EXPIRED";
  assert.equal((await f.service.authorizeReport(UID, report)).authorized, true);
  f.orders.set("GPA.plus", { state: "REFUNDED", purchaseToken: "plus" });
  await assert.rejects(f.service.authorizeReport(UID, report));
});
test("owner needs existing immutable draft and does not consume Plus quota", async () => {
  const f = setup(); const report = await f.prepare("horatrack_annual_review");
  assert.equal((await f.service.authorizeReport(UID, report, true)).authorized, true);
  assert.equal([...f.db.rows.keys()].filter(k => k.includes("analysisPeriods")).length, 0);
  await assert.rejects(f.service.authorizeReport(UID, { reportId: sha("absent"), documentSha256: sha("absent") }, true));
});
test("verified legacy bulletin credit restores and finances exactly one prepared report", async () => {
  const f = setup(); const token = "legacy-token";
  f.receipts.set(token, { purchaseState: 0, consumptionState: 1, obfuscatedExternalAccountId: sha(UID) });
  f.db.rows.set("billingPurchasesV1/" + sha(token), { uid: UID, productId: "horatrack_analysis", kind: "analysis", token, documentSha256: null, reportId: null, analysisUsed: false });
  await f.service.verify(UID, { productId: "horatrack_analysis", purchaseToken: token });
  assert.equal((await f.service.entitlements(UID)).analysisCredits, 1);
  const first = await f.prepare("horatrack_analysis", "legacy-first"), second = await f.prepare("horatrack_analysis", "legacy-second");
  await assert.rejects(f.service.authorizeReport(UID, first));
  await f.service.authorizeReport(UID, { ...first, useAnalysisCredit: true });
  await f.service.authorizeReport(UID, first);
  assert.equal((await f.service.entitlements(UID)).analysisCredits, 0);
  await assert.rejects(f.service.authorizeReport(UID, { ...second, useAnalysisCredit: true }));
  await assert.rejects(f.service.verify(UID, { productId: "horatrack_analysis", purchaseToken: "new-unbound" }));
  f.receipts.get(token).purchaseState = 1;
  await assert.rejects(f.service.authorizeReport(UID, first));
});
test("concurrent same report using Plus and legacy spends exactly one funding source", async () => {
  const f = setup(); await f.subscription(); const token = "legacy-concurrent";
  f.receipts.set(token, { purchaseState: 0, consumptionState: 1, obfuscatedExternalAccountId: sha(UID) });
  f.db.rows.set("billingPurchasesV1/" + sha(token), { uid: UID, productId: "horatrack_analysis", kind: "analysis", token, documentSha256: null, reportId: null, analysisUsed: false });
  const report = await f.prepare("horatrack_analysis", "same-report-concurrent");
  const results = await Promise.all([f.service.authorizeReport(UID, { ...report, usePlusCredit: true }), f.service.authorizeReport(UID, { ...report, useAnalysisCredit: true })]);
  assert(results.every(r => r.authorized));
  const quotaUsed = [...f.db.rows.keys()].filter(k => k.includes("analysisPeriods")).length;
  const legacyUsed = f.db.rows.get("billingPurchasesV1/" + sha(token)).analysisUsed ? 1 : 0;
  assert.equal(quotaUsed + legacyUsed, 1);
  const proof = f.db.rows.get("billingUsersV1/" + UID + "/serviceReports/" + report.reportId);
  assert.equal(proof.funding, quotaUsed ? "plus" : "legacy");
  assert.equal((await f.service.authorizeReport(UID, report)).authorized, true);
});
