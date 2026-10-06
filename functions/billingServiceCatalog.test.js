"use strict";
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { SERVICES, validateServiceOrder } = require("./billingServiceCatalog");
const { purchaseRequest, createBillingService } = require("./playBilling");
const valid = productId => ({ productId, requestId: "order_request_123", inputSha256: "a".repeat(64) });
test("four distinct services retain approved target prices and own PDF inclusion", () => {
  assert.deepEqual(Object.values(SERVICES).map(s => s.targetPriceEuroCents), [499, 699, 999, 1499]);
  assert.equal(new Set(Object.values(SERVICES).map(s => s.serviceId)).size, 4);
  assert(Object.values(SERVICES).every(s => s.pdfIncluded && s.availability === "DELIVERY_NOT_READY"));
  assert(Object.isFrozen(SERVICES));
});
test("owner premium purchasedPDF and client available flags cannot unlock missing service", () => {
  for (const productId of Object.keys(SERVICES)) {
    assert.throws(() => validateServiceOrder({ ...valid(productId), owner: true, premium: true, availability: "AVAILABLE", pdfIncluded: true }), e => e.code === "failed-precondition");
  }
});
test("invalid command identity and immutable input hash are rejected", () => {
  for (const data of [null, {}, valid("horatrack_pdf"), { ...valid("horatrack_analysis"), requestId: "short" }, { ...valid("horatrack_analysis"), inputSha256: "document" }]) {
    assert.throws(() => validateServiceOrder(data), e => e.code === "invalid-argument");
  }
});
test("new service purchase identifiers reject before Play calls", () => {
  for (const productId of Object.keys(SERVICES).filter(id => id !== "horatrack_analysis")) {
    assert.throws(() => purchaseRequest({ productId, purchaseToken: "valid-looking-token" }), e => e.code === "failed-precondition");
  }
  assert.equal(purchaseRequest({ productId: "horatrack_analysis", purchaseToken: "legacy-token" }).kind, "analysis");
});
test("missing report pipeline cannot reserve or consume credit even for trusted owner", async () => {
  let touched = false;
  const service = createBillingService({ db: { collection() { return { doc() { touched = true; throw Error("Unexpected write"); } }; } }, play: { async get() { touched = true; throw Error("Unexpected verification"); } } });
  for (const productId of Object.keys(SERVICES)) {
    await assert.rejects(service.reserveAnalysis("uid", valid(productId), true), e => e.code === "failed-precondition");
  }
  assert.equal(touched, false);
});
