"use strict";
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { SERVICES, validateServiceOrder } = require("./billingServiceCatalog");
const { purchaseRequest, createBillingService } = require("./playBilling");
const valid = productId => ({ productId, requestId: "order_request_123", inputSha256: "a".repeat(64) });
test("four distinct services retain approved target prices and own PDF inclusion", () => {
  assert.deepEqual(Object.values(SERVICES).map(s => s.targetPriceEuroCents), [499, 699, 999, 1499]);
  assert.equal(new Set(Object.values(SERVICES).map(s => s.serviceId)).size, 4);
  assert(Object.values(SERVICES).every(s => s.pdfIncluded && s.availability === "AVAILABLE"));
  assert(Object.isFrozen(SERVICES));
});
test("service order strips client claimed owner and premium rights", () => {
  const order = validateServiceOrder({ ...valid("horatrack_analysis"), owner: true, premium: true });
  assert.equal(order.serviceId, "payslip_analysis"); assert.equal(order.owner, undefined); assert.equal(order.premium, undefined);
});

test("invalid command identity and immutable input hash are rejected", () => {
  for (const data of [null, {}, valid("horatrack_pdf"), { ...valid("horatrack_analysis"), requestId: "short" }, { ...valid("horatrack_analysis"), inputSha256: "document" }]) {
    assert.throws(() => validateServiceOrder(data), e => e.code === "invalid-argument");
  }
});
test("service receipt identifiers require immutable document hash", () => {
  for (const productId of Object.keys(SERVICES)) {
    if (productId !== "horatrack_analysis") assert.throws(() => purchaseRequest({ productId, purchaseToken: "token" }), e => e.code === "invalid-argument");
    assert.equal(purchaseRequest({ productId, purchaseToken: "token", documentSha256: "a".repeat(64) }).documentSha256, "a".repeat(64));
  }
});
test("cannot spend any credit before report exists even for owner", async () => {
  const service = createBillingService({ db: { collection() { return {}; } }, play: {} });
  await assert.rejects(service.reserveAnalysis("uid", valid("horatrack_analysis"), true), e => e.code === "failed-precondition");
});
