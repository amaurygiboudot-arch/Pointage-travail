"use strict";
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { sha, verifyState, purchaseRequest, createBillingService, createPlayClient, BillingError } = require("./playBilling");
const UID = "user";
const PDF = sha("pdf bytes");
const good = () => ({ purchaseState: 0, consumptionState: 0, acknowledgementState: 0, obfuscatedExternalAccountId: sha(UID), obfuscatedExternalProfileId: PDF });
const { memoryDb } = require("./billingTestFixtures");

test("purchase parser rejects forged products and missing document identity", () => {
  assert.throws(() => purchaseRequest({ productId: "free", purchaseToken: "x" }));
  assert.throws(() => purchaseRequest({ productId: "horatrack_pdf", purchaseToken: "x", documentSha256: "document1" }));
});
test("pending refunds account mismatch and expired subscriptions fail closed", () => {
  const p = { kind: "pdf", productId: "horatrack_pdf", documentSha256: PDF };
  for (const purchaseState of [1, 2, undefined]) assert.throws(() => verifyState({ ...good(), purchaseState }, p, UID));
  assert.throws(() => verifyState(good(), p, "other"));
  assert.throws(() => verifyState({ ...good(), obfuscatedExternalProfileId: sha("other PDF") }, p, UID));
  assert.throws(() => verifyState({ ...good(), obfuscatedExternalProfileId: undefined }, p, UID));
  const subscription = { externalAccountIdentifiers: { obfuscatedExternalAccountId: sha(UID) }, subscriptionState: "SUBSCRIPTION_STATE_ACTIVE", lineItems: [{ productId: "horatrack_plus", expiryTime: "2026-12-01T00:00:00Z" }] };
  assert.equal(verifyState(subscription, { kind: "plus", productId: "horatrack_plus" }, UID, Date.parse("2026-10-06")).expiryMs, Date.parse("2026-12-01"));
  assert.throws(() => verifyState({ ...subscription, subscriptionState: "SUBSCRIPTION_STATE_ON_HOLD" }, { kind: "plus", productId: "horatrack_plus" }, UID));
  assert.throws(() => verifyState(subscription, { kind: "plus", productId: "horatrack_plus" }, UID, Date.parse("2027-01-01")));
});
test("PDF token is idempotent document-bound redownloadable and revoked on refund", async () => {
  const db = memoryDb(); let raw = good(); let settled = 0;
  const service = createBillingService({ db, play: { get: async () => raw, settle: async () => { settled++; raw = { ...raw, consumptionState: 1 }; } } });
  const data = { productId: "horatrack_pdf", purchaseToken: "token", documentSha256: PDF };
  await assert.rejects(service.authorizePdf(UID, { documentSha256: PDF }));
  await service.verify(UID, data); await service.verify(UID, data);
  assert.equal(settled, 2); assert.equal(db.rows.size, 1);
  assert.equal((await service.authorizePdf(UID, { documentSha256: PDF })).authorized, true);
  await assert.rejects(service.verify(UID, { ...data, documentSha256: sha("different") }));
  await assert.rejects(service.authorizePdf(UID, { documentSha256: sha("different") }));
  await assert.rejects(service.verify("other", data));
  raw = { ...raw, purchaseState: 1 };
  await assert.rejects(service.authorizePdf(UID, { documentSha256: PDF }));
});
test("service receipt requires a prepared report and never grants arbitrary PDF", async () => {
  const service = createBillingService({ db: memoryDb(), play: { get: async () => good(), settle: async () => {} } });
  await assert.rejects(service.verify(UID, { productId: "horatrack_analysis", purchaseToken: "analysis-token", documentSha256: PDF }), e => e.code === "permission-denied");
  await assert.rejects(service.reserveAnalysis(UID, { requestId: "analysis_request_1", inputSha256: PDF }), e => e.code === "failed-precondition");
  await assert.rejects(service.authorizePdf(UID, { documentSha256: PDF }));
});

test("owner entitlement comes from trusted argument and API outages deny access", async () => {
  const db = memoryDb(); let broken = false;
  const service = createBillingService({ db, play: { get: async () => { if (broken) throw Error("offline"); return good(); }, settle: async () => {} } });
  assert.equal((await service.authorizePdf(UID, { documentSha256: PDF }, true)).authorized, true);
  await service.verify(UID, { productId: "horatrack_pdf", purchaseToken: "token", documentSha256: PDF }); broken = true;
  await assert.rejects(service.authorizePdf(UID, { documentSha256: PDF }));
});
test("subscription PDF remains downloadable after expiry but refund revokes it", async () => {
  const db = memoryDb(); let expired = false; let refunded = false;
  const token = "subscription-token";
  const service = createBillingService({ db, now: () => Date.parse("2026-10-06"), play: {
    get: async () => ({ externalAccountIdentifiers: { obfuscatedExternalAccountId: sha(UID) }, subscriptionState: expired ? "SUBSCRIPTION_STATE_EXPIRED" : "SUBSCRIPTION_STATE_ACTIVE", lineItems: [{ productId: "horatrack_premium", expiryTime: "2026-12-01T00:00:00Z", latestSuccessfulOrderId: "GPA.1" }] }),
    settle: async () => {}, order: async () => ({ state: refunded ? "REFUNDED" : "PROCESSED", purchaseToken: token }),
  } });
  await service.verify(UID, { productId: "horatrack_premium", purchaseToken: token });
  assert.equal((await service.authorizePdf(UID, { documentSha256: PDF })).authorized, true);
  expired = true;
  assert.equal((await service.authorizePdf(UID, { documentSha256: PDF })).authorized, true);
  await assert.rejects(service.authorizePdf(UID, { documentSha256: sha("new document") }));
  refunded = true;
  await assert.rejects(service.authorizePdf(UID, { documentSha256: PDF }));
});
test("Play requests use fixed package and consume verified consumable server side", async () => {
  const seen = [];
  const client = createPlayClient({ credential: { getAccessToken: async () => ({ access_token: "server-credential" }) }, fetchImpl: async (url, options) => { seen.push({ url, options }); return { ok: true, status: 204 }; } });
  await client.settle({ productId: "horatrack_pdf", token: "a/b", kind: "pdf" }, { consumed: false });
  assert.match(seen[0].url, /com\.amaury\.pointage/);
  assert.match(seen[0].url, /a%2Fb:consume$/);
  assert.equal(seen[0].options.method, "POST");
  await client.order("GPA.1");
  assert.match(seen[1].url, /applications\/com\.amaury\.pointage\/orders\/GPA\.1$/);
});
test("empty HTTP 200 settlement succeeds for PDF and subscriptions, verification still requires JSON", async () => {
  const requests = [];
  const client = createPlayClient({
    credential: { getAccessToken: async () => ({ access_token: "credential" }) },
    fetchImpl: async (url, options) => {
      requests.push({ url, method: options.method });
      return new Response("", { status: 200 });
    },
  });
  await client.settle({ productId: "horatrack_pdf", token: "pdf", kind: "pdf" }, { consumed: false });
  await client.settle({ productId: "horatrack_premium", token: "premium", kind: "premium" }, { acknowledged: false });
  assert.match(requests[0].url, /:consume$/);
  assert.match(requests[1].url, /:acknowledge$/);
  assert.deepEqual(requests.map(r => r.method), ["POST", "POST"]);
  await assert.rejects(client.get({ productId: "horatrack_pdf", token: "pdf", kind: "pdf" }), SyntaxError);
  await assert.rejects(client.order("GPA.1"), SyntaxError);
});
test("empty settlement bodies complete verified purchases without duplicated rights", async () => {
  const db = memoryDb();
  let consumed = false;
  const play = createPlayClient({
    credential: { getAccessToken: async () => ({ access_token: "credential" }) },
    fetchImpl: async (_url, options) => {
      if (options.method === "POST") { consumed = true; return new Response("", { status: 200 }); }
      return Response.json({ ...good(), consumptionState: consumed ? 1 : 0 });
    },
  });
  const service = createBillingService({ db, play });
  const purchase = { productId: "horatrack_pdf", purchaseToken: "empty-body", documentSha256: PDF };
  await service.verify(UID, purchase);
  await service.verify(UID, purchase);
  assert.equal(db.rows.size, 1);
  assert.equal((await service.authorizePdf(UID, { documentSha256: PDF })).authorized, true);
});
test("linked subscription replaces predecessor and cannot cross UID ownership", async () => {
  const db = memoryDb();
  const old = "old-token", next = "new-token";
  const play = { get: async p => ({ externalAccountIdentifiers: { obfuscatedExternalAccountId: sha(UID) }, subscriptionState: "SUBSCRIPTION_STATE_ACTIVE", linkedPurchaseToken: p.token === next ? old : undefined, lineItems: [{ productId: p.productId, expiryTime: "2027-01-01T00:00:00Z", latestSuccessfulOrderId: "GPA.1" }] }), settle: async () => {} };
  const service = createBillingService({ db, play });
  await service.verify(UID, { productId: "horatrack_premium", purchaseToken: old });
  await service.verify(UID, { productId: "horatrack_plus", purchaseToken: next });
  assert.equal(db.rows.get("billingPurchasesV1/" + sha(old)).supersededBy, sha(next));
  db.rows.get("billingPurchasesV1/" + sha(old)).uid = "other";
  await assert.rejects(service.verify(UID, { productId: "horatrack_plus", purchaseToken: next }));
});
test("permanent Play errors deny while transient errors remain unavailable", async () => {
  for (const status of [404, 410, 403, 429, 500]) {
    const client = createPlayClient({ credential: { getAccessToken: async () => ({ access_token: "token" }) }, fetchImpl: async () => ({ ok: false, status }) });
    await assert.rejects(client.get({ kind: "analysis", productId: "horatrack_analysis", token: "token" }), error => error.code === ([404, 410].includes(status) ? "permission-denied" : "unavailable"));
  }
});
test("unseen replaced predecessor receives tombstone and cannot later grant rights", async () => {
  const db = memoryDb();
  const service = createBillingService({ db, now: () => Date.parse("2026-10-06"), play: {
    get: async p => ({ externalAccountIdentifiers: { obfuscatedExternalAccountId: sha(UID) }, subscriptionState: "SUBSCRIPTION_STATE_ACTIVE", linkedPurchaseToken: p.token === "new" ? "unseen-old" : undefined, lineItems: [{ productId: p.productId, expiryTime: "2027-01-01T00:00:00Z", latestSuccessfulOrderId: "GPA.new" }] }), settle: async () => {},
  } });
  await service.verify(UID, { productId: "horatrack_plus", purchaseToken: "new" });
  assert.equal(db.rows.get("billingPurchasesV1/" + sha("unseen-old")).supersededBy, sha("new"));
  await assert.rejects(service.verify(UID, { productId: "horatrack_premium", purchaseToken: "unseen-old" }));
});
test("invalid historical PDF proof allows verified repurchase but not transient fallback", async () => {
  const db = memoryDb(); let oldState = "PROCESSED";
  const service = createBillingService({ db, now: () => Date.parse("2026-10-06"), play: {
    get: async p => ({ externalAccountIdentifiers: { obfuscatedExternalAccountId: sha(UID) }, subscriptionState: p.token === "old" && oldState !== "PROCESSED" ? "SUBSCRIPTION_STATE_EXPIRED" : "SUBSCRIPTION_STATE_ACTIVE", lineItems: [{ productId: p.productId, expiryTime: "2027-01-01T00:00:00Z", latestSuccessfulOrderId: "GPA." + p.token }] }),
    settle: async () => {}, order: async id => { if (id === "GPA.old" && oldState === "unavailable") throw new BillingError("unavailable", "offline"); if (id === "GPA.old" && oldState === "gone") throw new BillingError("permission-denied", "gone"); return { state: id === "GPA.old" ? oldState : "PROCESSED", purchaseToken: id.slice(4) }; },
  } });
  await service.verify(UID, { productId: "horatrack_premium", purchaseToken: "old" });
  await service.authorizePdf(UID, { documentSha256: PDF });
  await service.verify(UID, { productId: "horatrack_premium", purchaseToken: "new" });
  oldState = "unavailable";
  await assert.rejects(service.authorizePdf(UID, { documentSha256: PDF }));
  oldState = "gone";
  assert.equal((await service.authorizePdf(UID, { documentSha256: PDF })).authorized, true);
  assert.equal(db.rows.get("billingUsersV1/user/ownedPdfs/" + PDF).orderId, "GPA.new");
});
