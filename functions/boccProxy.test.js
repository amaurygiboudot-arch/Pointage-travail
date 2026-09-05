"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const {
  isValidLegifranceBody,
  normalizeLegifranceBody,
  publicFailureMessage,
} = require("./legifranceProxy");

test("normalizes BOCC listing to one scalar IDCC and a bounded publication window", () => {
  const body = {
    idccs: ["IDCC 3248"],
    intervalPublication: "01/09/2024   >   30/09/2026",
    pageNumber: -4,
    pageSize: 500,
    sortValue: "UNSUPPORTED",
  };

  assert.equal(isValidLegifranceBody("/list/boccsAndTexts", body), true);
  assert.deepEqual(normalizeLegifranceBody("/list/boccsAndTexts", body), {
    idcc: "3248",
    intervalPublication: "01/09/2024 > 30/09/2026",
    pageNumber: 1,
    pageSize: 100,
    sortValue: "BOCC_SORT_DESC",
  });

  assert.equal(isValidLegifranceBody("/list/boccsAndTexts", {
    idcc: "3248",
    intervalPublication: "2024-09-01 to 2026-09-30",
  }), false);
  assert.equal(isValidLegifranceBody("/list/boccsAndTexts", {
    idcc: "aucun",
    intervalPublication: "01/09/2024 > 30/09/2026",
  }), false);
});

test("accepts a legacy idccs array but emits the boccsAndTexts scalar idcc", () => {
  const normalized = normalizeLegifranceBody("/list/boccsAndTexts", {
    idccs: ["0292"],
    intervalPublication: "01/09/2024 > 30/09/2026",
    pageNumber: 1,
    pageSize: 25,
  });
  assert.equal(normalized.idcc, "292");
});

test("accepts only safe BOCC PDF metadata identifiers", () => {
  const path = "/consult/getBoccTextPdfMetadata";
  assert.equal(isValidLegifranceBody(path, { id: "boc_20260024_0001_p000.pdf" }), true);
  assert.equal(isValidLegifranceBody(path, { id: "nom avec espace.pdf" }), false);
  assert.equal(isValidLegifranceBody(path, { id: "boc_20260024_0001_p000.exe" }), false);
  assert.deepEqual(normalizeLegifranceBody(path, {
    id: "boc_20260024_0001_p000.pdf",
    forGlobalBocc: true,
    note: "ignored",
  }), {
    id: "boc_20260024_0001_p000.pdf",
    forGlobalBocc: false,
  });
});

test("labels BOCC upstream validation failures without exposing a response body", () => {
  assert.match(publicFailureMessage("api", 400, "/consult/getBoccTextPdfMetadata"), /BOCC/);
});
