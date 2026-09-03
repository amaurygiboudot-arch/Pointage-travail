"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { normalizeLegifranceBody, publicFailureMessage } = require("./legifranceProxy");

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

test("returns actionable messages without upstream response bodies", () => {
  assert.match(publicFailureMessage("oauth", 401), /OAuth 401/);
  assert.match(publicFailureMessage("api", 403), /HTTP 403/);
  assert.match(publicFailureMessage("timeout", 0), /délai dépassé/);
});
