"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { normalizeBoccRequest } = require("./boccRequest");

test("normalizes BOCC to the minimal documented boccTexts payload", () => {
  assert.deepEqual(normalizeBoccRequest({
    idccs: ["IDCC 0292"],
    intervalPublication: "05/09/2024   >   05/09/2026",
    pageNumber: -4,
    pageSize: 500,
    sortValue: "UNKNOWN",
    searchForGlobalBocc: false,
    searchForTextsBocc: true,
  }), {
    idccs: ["292"],
    intervalPublication: "05/09/2024 > 05/09/2026",
    pageNumber: 1,
    pageSize: 100,
    sortValue: "BOCC_SORT_DESC",
  });
});

test("rejects BOCC requests without a valid IDCC or interval", () => {
  assert.deepEqual(normalizeBoccRequest({
    idccs: ["aucun"],
    intervalPublication: "05/09/2024 > 05/09/2026",
  }), {});
  assert.deepEqual(normalizeBoccRequest({
    idccs: ["292"],
    intervalPublication: "2024-09-05 to 2026-09-05",
  }), {});
});
