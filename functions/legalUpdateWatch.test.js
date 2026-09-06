"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const {
  LEGAL_CACHE_SCHEMA_VERSION,
  LEGAL_CACHE_PARSER_VERSION,
  ACCO_SEARCH_TTL_MS,
  JORF_FEED_TTL_MS,
  cacheSpec,
  payloadHash,
} = require("./legalKaliCache");
const {
  WATCH_COLLECTIONS,
  parseWatchRequest,
  refreshWatchDocument,
  refreshDueLegalWatches,
} = require("./legalUpdateWatch");

function makeDoc(id, data) {
  const state = { ...data };
  return {
    id,
    ref: {
      async set(value, options) {
        if (options?.merge) Object.assign(state, value);
        else {
          Object.keys(state).forEach((key) => delete state[key]);
          Object.assign(state, value);
        }
      },
    },
    data: () => ({ ...state }),
    state,
  };
}

function fakeQueryDb(collectionDocs = {}) {
  return {
    collection(name) {
      const docs = collectionDocs[name] || [];
      return {
        where(field, operator, value) {
          assert.equal(field, "nextCheckAtMs");
          assert.equal(operator, "<=");
          const filtered = docs.filter((doc) => Number(doc.state.nextCheckAtMs) <= value);
          return {
            orderBy(orderField, direction) {
              assert.equal(orderField, "nextCheckAtMs");
              assert.equal(direction, "asc");
              const sorted = [...filtered].sort((a, b) => a.state.nextCheckAtMs - b.state.nextCheckAtMs);
              return {
                limit(valueLimit) {
                  return {
                    async get() {
                      return { docs: sorted.slice(0, valueLimit) };
                    },
                  };
                },
              };
            },
          };
        },
      };
    },
  };
}

const quietLogger = { info() {}, warn() {} };

test("la veille ne recharge que les entrées cohérentes avec leur collection et leur identifiant", () => {
  const body = {
    fond: "ACCO",
    recherche: {
      filtres: [{ facette: "SIRET_RAISON_SOCIALE", valeurs: ["12345678901234"] }],
    },
  };
  const spec = cacheSpec("/search", body);
  const data = {
    schemaVersion: LEGAL_CACHE_SCHEMA_VERSION,
    parserVersion: LEGAL_CACHE_PARSER_VERSION,
    watchEnabled: true,
    path: "/search",
    requestJson: JSON.stringify(body),
  };

  assert.ok(parseWatchRequest(spec.collection, spec.documentId, data));
  assert.equal(parseWatchRequest(spec.collection, "mauvais-id", data), null);
  assert.equal(parseWatchRequest(spec.collection, spec.documentId, { ...data, watchEnabled: false }), null);
  assert.equal(parseWatchRequest(spec.collection, spec.documentId, { ...data, requestJson: "{" }), null);
});

test("un rafraîchissement planifié identique prolonge la fraîcheur sans créer de faux changement", async () => {
  const nowMs = 1_800_000_000_000;
  const body = {
    fond: "ACCO",
    recherche: {
      filtres: [{ facette: "SIRET_RAISON_SOCIALE", valeurs: ["12345678901234"] }],
    },
  };
  const spec = cacheSpec("/search", body);
  const oldPayload = JSON.stringify({ results: [{ id: "ACCOTEXT1" }] });
  const doc = makeDoc(spec.documentId, {
    schemaVersion: LEGAL_CACHE_SCHEMA_VERSION,
    parserVersion: LEGAL_CACHE_PARSER_VERSION,
    watchEnabled: true,
    sourceFamily: "ACCO",
    path: "/search",
    requestJson: JSON.stringify(body),
    nextCheckAtMs: nowMs - 1,
    expiresAtMs: nowMs - 1,
    payloadJson: oldPayload,
    payloadHash: payloadHash(oldPayload),
    scopeType: "SIRET",
    scopeValue: "12345678901234",
    changeCount: 0,
  });

  const result = await refreshWatchDocument({
    collection: spec.collection,
    documentId: spec.documentId,
    ref: doc.ref,
    data: doc.data(),
    fetchOfficial: async () => ({ results: [{ id: "ACCOTEXT1" }] }),
    now: () => nowMs,
    logger: quietLogger,
  });

  assert.equal(result.status, "refreshed");
  assert.equal(result.changed, false);
  assert.equal(doc.state.nextCheckAtMs, nowMs + ACCO_SEARCH_TTL_MS);
  assert.equal(doc.state.expiresAtMs, nowMs + ACCO_SEARCH_TTL_MS);
  assert.equal(doc.state.changeCount, 0);
  assert.equal(doc.state.refreshFailureCount, 0);
});

test("un changement JORF planifié est marqué sans appliquer lui-même une règle de paie", async () => {
  const nowMs = 1_800_000_000_000;
  const body = { nbElement: 5 };
  const spec = cacheSpec("/consult/lastNJo", body);
  const oldPayload = JSON.stringify({ containers: ["JORFCONT1"] });
  const doc = makeDoc(spec.documentId, {
    schemaVersion: LEGAL_CACHE_SCHEMA_VERSION,
    parserVersion: LEGAL_CACHE_PARSER_VERSION,
    watchEnabled: true,
    sourceFamily: "JORF",
    path: "/consult/lastNJo",
    requestJson: JSON.stringify(body),
    nextCheckAtMs: nowMs - 1,
    expiresAtMs: nowMs - 1,
    payloadJson: oldPayload,
    payloadHash: payloadHash(oldPayload),
    scopeType: "GLOBAL",
    scopeValue: "FRANCE",
    changeCount: 4,
  });

  const result = await refreshWatchDocument({
    collection: spec.collection,
    documentId: spec.documentId,
    ref: doc.ref,
    data: doc.data(),
    fetchOfficial: async () => ({ containers: ["JORFCONT1", "JORFCONT2"] }),
    now: () => nowMs,
    logger: quietLogger,
  });

  assert.equal(result.changed, true);
  assert.equal(doc.state.changeCount, 5);
  assert.equal(doc.state.changeDetectedAtMs, nowMs);
  assert.equal(doc.state.nextCheckAtMs, nowMs + JORF_FEED_TTL_MS);
  assert.equal(doc.state.scopeType, "GLOBAL");
  assert.equal(doc.state.scopeValue, "FRANCE");
});

test("le balayage planifié respecte une limite globale et garde les erreurs pour nouvelle tentative", async () => {
  const nowMs = 1_800_000_000_000;
  const body1 = {
    fond: "ACCO",
    recherche: {
      filtres: [{ facette: "SIRET_RAISON_SOCIALE", valeurs: ["12345678901234"] }],
    },
  };
  const spec1 = cacheSpec("/search", body1);
  const body2 = { nbElement: 5 };
  const spec2 = cacheSpec("/consult/lastNJo", body2);

  const acco = makeDoc(spec1.documentId, {
    schemaVersion: LEGAL_CACHE_SCHEMA_VERSION,
    parserVersion: LEGAL_CACHE_PARSER_VERSION,
    watchEnabled: true,
    sourceFamily: "ACCO",
    path: "/search",
    requestJson: JSON.stringify(body1),
    nextCheckAtMs: nowMs - 10,
    expiresAtMs: nowMs - 10,
    payloadJson: JSON.stringify({ results: [] }),
  });
  const jorf = makeDoc(spec2.documentId, {
    schemaVersion: LEGAL_CACHE_SCHEMA_VERSION,
    parserVersion: LEGAL_CACHE_PARSER_VERSION,
    watchEnabled: true,
    sourceFamily: "JORF",
    path: "/consult/lastNJo",
    requestJson: JSON.stringify(body2),
    nextCheckAtMs: nowMs - 5,
    expiresAtMs: nowMs - 5,
    payloadJson: JSON.stringify({ containers: [] }),
  });

  const db = fakeQueryDb({
    legal_acco_search: [acco],
    legal_jorf_feed: [jorf],
  });
  let calls = 0;
  const summary = await refreshDueLegalWatches({
    db,
    now: () => nowMs,
    batchSize: 2,
    logger: quietLogger,
    fetchOfficial: async (path) => {
      calls += 1;
      if (path === "/consult/lastNJo") throw new Error("jorf-offline");
      return { results: [{ id: "ACCOTEXT1" }] };
    },
  });

  assert.deepEqual(summary, {
    scanned: 2,
    refreshed: 1,
    changed: 1,
    failed: 1,
    skipped: 0,
  });
  assert.equal(calls, 2);
  assert.equal(jorf.state.nextCheckAtMs, nowMs - 5);
  assert.equal(jorf.state.expiresAtMs, nowMs - 5);
  assert.equal(jorf.state.refreshFailureCount, 1);
  assert.equal(jorf.state.lastRefreshErrorAtMs, nowMs);
});

test("la liste des collections surveillées reste limitée aux recherches et flux dynamiques", () => {
  assert.deepEqual(WATCH_COLLECTIONS, [
    "legal_acco_search",
    "legal_kali_search",
    "legal_legi_search",
    "legal_bocc_search",
    "legal_jorf_feed",
  ]);
});
