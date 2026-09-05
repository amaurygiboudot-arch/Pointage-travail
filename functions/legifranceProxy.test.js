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

function codeDateBody() {
  return {
    fond: "CODE_DATE",
    recherche: {
      champs: [{
        typeChamp: "ARTICLE",
        criteres: [{ typeRecherche: "UN_DES_MOTS", valeur: "heures supplémentaires", operateur: "ET" }],
        operateur: "ET",
      }],
      filtres: [
        { facette: "NOM_CODE", valeurs: ["Code du travail"] },
        { facette: "DATE_VERSION", singleDate: 1788566400000 },
        { facette: "TEXT_LEGAL_STATUS", valeur: "VIGUEUR" },
      ],
      pageNumber: 7,
      pageSize: 500,
      sort: "DATE_DESC",
      typePagination: "ARTICLE",
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

test("rebuilds CODE_DATE keyword searches with the documented Code du travail shape", () => {
  const body = codeDateBody();
  assert.equal(isValidLegifranceBody("/search", body), true);

  const normalized = normalizeLegifranceBody("/search", body);
  assert.equal(normalized.fond, "CODE_DATE");
  assert.equal(normalized.recherche.pageNumber, 1);
  assert.equal(normalized.recherche.pageSize, 25);
  assert.equal(normalized.recherche.sort, "PERTINENCE");
  assert.equal(normalized.recherche.typePagination, "DEFAUT");
  assert.deepEqual(normalized.recherche.filtres, [
    { facette: "NOM_CODE", valeurs: ["Code du travail"] },
    { facette: "DATE_VERSION", singleDate: 1788566400000 },
  ]);
  assert.deepEqual(normalized.recherche.champs, [{
    typeChamp: "ARTICLE",
    criteres: [{ typeRecherche: "UN_DES_MOTS", valeur: "heures supplémentaires", operateur: "ET" }],
    operateur: "ET",
  }]);
});

test("rejects CODE_DATE searches without query or reference date", () => {
  const missingDate = codeDateBody();
  missingDate.recherche.filtres = missingDate.recherche.filtres.filter((item) => item.facette !== "DATE_VERSION");
  assert.equal(isValidLegifranceBody("/search", missingDate), false);

  const missingQuery = codeDateBody();
  missingQuery.recherche.champs[0].criteres[0].valeur = "   ";
  assert.equal(isValidLegifranceBody("/search", missingQuery), false);
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

test("accepts only official LEGI identifiers for article consultation", () => {
  assert.equal(isValidLegifranceBody("/consult/getArticle", { id: "LEGIARTI000033219357" }), true);
  assert.equal(isValidLegifranceBody("/consult/getArticle", { id: "JORFTEXT000033219357" }), false);
  assert.deepEqual(normalizeLegifranceBody("/consult/getArticle", { id: " legiarti000033219357 ", ignored: true }), {
    id: "LEGIARTI000033219357",
  });
});

test("validates and minimizes LEGI full text requests", () => {
  const date = 1788566400000;
  assert.equal(isValidLegifranceBody("/consult/legiPart", { textId: "LEGITEXT000038359719", date }), true);
  assert.equal(isValidLegifranceBody("/consult/legiPart", { textId: "LEGITEXTbad", date }), false);
  assert.deepEqual(normalizeLegifranceBody("/consult/legiPart", {
    textId: " legitext000038359719 ",
    date,
    ignored: "secret",
  }), {
    date,
    textId: "LEGITEXT000038359719",
  });
});

test("bounds JORF container requests and validates official IDs", () => {
  assert.equal(isValidLegifranceBody("/consult/lastNJo", { nbElement: 5 }), true);
  assert.equal(isValidLegifranceBody("/consult/lastNJo", { nbElement: 2500 }), false);
  assert.deepEqual(normalizeLegifranceBody("/consult/lastNJo", { nbElement: 500 }), { nbElement: 100 });

  assert.equal(isValidLegifranceBody("/consult/jorfCont", { id: "JORFCONT000022470431" }), true);
  assert.deepEqual(normalizeLegifranceBody("/consult/jorfCont", {
    id: "jorfcont000022470431",
    pageNumber: -2,
    pageSize: 500,
    highlightActivated: false,
  }), {
    highlightActivated: false,
    id: "JORFCONT000022470431",
    pageNumber: 1,
    pageSize: 100,
  });
});

test("accepts only JORFTEXT identifiers for JORF content", () => {
  assert.equal(isValidLegifranceBody("/consult/jorf", { textCid: "JORFTEXT000033736934" }), true);
  assert.equal(isValidLegifranceBody("/consult/jorf", { textCid: "LEGITEXT000033736934" }), false);
  assert.deepEqual(normalizeLegifranceBody("/consult/jorf", { textCid: " jorftext000033736934 ", ignored: true }), {
    textCid: "JORFTEXT000033736934",
  });
});

test("returns actionable messages without upstream response bodies", () => {
  assert.match(publicFailureMessage("oauth", 401), /OAuth 401/);
  assert.match(publicFailureMessage("api", 403), /HTTP 403/);
  assert.match(publicFailureMessage("api", 400, "/consult/kaliContIdcc"), /KALI/);
  assert.match(publicFailureMessage("api", 400, "/consult/getArticle"), /LEGI/);
  assert.match(publicFailureMessage("api", 400, "/consult/jorf"), /JORF/);
  assert.match(publicFailureMessage("timeout", 0), /délai dépassé/);
});
