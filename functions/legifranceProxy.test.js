"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const {
  isValidLegifranceBody,
  normalizeLegifranceBody,
  publicFailureMessage,
} = require("./legifranceProxy");

function accoBody() {
  return {
    fond: "ACCO",
    recherche: {
      champs: [{
        typeChamp: "ALL",
        criteres: [{ typeRecherche: "EXACTE", valeur: "12345678901234", operateur: "ET" }],
        operateur: "ET",
      }],
      pageNumber: 1,
      pageSize: 25,
      operateur: "ET",
      sort: "PERTINENCE",
      typePagination: "DEFAUT",
    },
  };
}

test("adds the official SIRET filter to legacy ACCO searches", () => {
  const normalized = normalizeLegifranceBody("/search", accoBody());
  assert.deepEqual(normalized.recherche.filtres, [
    { valeurs: ["12345678901234"], facette: "SIRET_RAISON_SOCIALE" },
  ]);
  assert.equal(normalized.recherche.sort, "DATE_DESC");
  assert.equal(normalized.recherche.fromAdvancedRecherche, false);
  assert.equal(normalized.recherche.secondSort, "ID");
});

test("does not duplicate an existing SIRET filter", () => {
  const body = accoBody();
  body.recherche.filtres = [{ valeurs: ["12345678901234"], facette: "SIRET_RAISON_SOCIALE" }];
  const normalized = normalizeLegifranceBody("/search", body);
  assert.equal(normalized.recherche.filtres.length, 1);
});

test("never changes KALI payloads", () => {
  const body = { fond: "KALI", recherche: { sort: "PERTINENCE" } };
  assert.strictEqual(normalizeLegifranceBody("/search", body), body);
});

test("normalizes a KALI container lookup to a numeric IDCC", () => {
  assert.deepEqual(normalizeLegifranceBody("/consult/kaliContIdcc", { id: "IDCC 0292", ignored: true }), {
    id: "292",
  });
  assert.equal(isValidLegifranceBody("/consult/kaliContIdcc", { id: "0292" }), true);
  assert.equal(isValidLegifranceBody("/consult/kaliContIdcc", { id: "not-an-idcc" }), false);
});

test("bounds the official convention catalogue request", () => {
  assert.deepEqual(normalizeLegifranceBody("/list/conventions", {
    pageNumber: 50,
    pageSize: 500,
    sort: "UNSUPPORTED",
    legalStatus: ["VIGUEUR", "ABROGE"],
  }), {
    pageNumber: 20,
    pageSize: 100,
    sort: "DATE_UPDATE",
    legalStatus: ["VIGUEUR"],
  });
});

test("returns actionable messages without upstream response bodies", () => {
  assert.match(publicFailureMessage("oauth", 401), /OAuth 401/);
  assert.match(publicFailureMessage("api", 403), /HTTP 403/);
  assert.match(publicFailureMessage("api", 400, "/consult/kaliContIdcc"), /KALI/);
  assert.match(publicFailureMessage("timeout", 0), /délai dépassé/);
});
