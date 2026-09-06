"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const {
  isValidLegifranceBody,
  normalizeLegifranceBody,
} = require("./legifranceProxy");

test("kaliArticle accepte uniquement un identifiant KALIARTI officiel", () => {
  assert.equal(isValidLegifranceBody("/consult/kaliArticle", { id: "KALIARTI000005833238" }), true);
  assert.equal(isValidLegifranceBody("/consult/kaliArticle", { id: "KALITEXT000005677408" }), false);
  assert.deepEqual(
    normalizeLegifranceBody("/consult/kaliArticle", { id: " kaliarti000005833238 ", ignored: "x" }),
    { id: "KALIARTI000005833238" }
  );
});

test("kaliText accepte uniquement un identifiant KALITEXT officiel", () => {
  assert.equal(isValidLegifranceBody("/consult/kaliText", { id: "KALITEXT000005677408" }), true);
  assert.equal(isValidLegifranceBody("/consult/kaliText", { id: "KALIARTI000005833238" }), false);
  assert.deepEqual(
    normalizeLegifranceBody("/consult/kaliText", { id: " kalitext000005677408 ", ignored: "x" }),
    { id: "KALITEXT000005677408" }
  );
});
