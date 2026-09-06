"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const {
  LEGAL_CACHE_SCHEMA_VERSION,
  LEGAL_CACHE_PARSER_VERSION,
  KALI_DOCUMENT_TTL_MS,
  KALI_SEARCH_TTL_MS,
  cacheSpec,
  resolveWithLegalCache,
} = require("./legalKaliCache");

function fakeFirestore(initial = new Map(), options = {}) {
  const store = initial;
  const calls = { gets: 0, sets: 0 };
  return {
    calls,
    store,
    db: {
      collection(collection) {
        return {
          doc(documentId) {
            const key = `${collection}/${documentId}`;
            return {
              async get() {
                calls.gets += 1;
                if (options.readError) throw new Error("firestore-read-failed");
                return {
                  exists: store.has(key),
                  data: () => store.get(key),
                };
              },
              async set(value) {
                calls.sets += 1;
                if (options.writeError) throw new Error("firestore-write-failed");
                store.set(key, value);
              },
            };
          },
        };
      },
    },
  };
}

const quietLogger = { info() {}, warn() {} };

test("configure 30 jours pour KALIARTI/KALITEXT et 7 jours pour les recherches IDCC", () => {
  assert.deepEqual(cacheSpec("/consult/kaliArticle", { id: "KALIARTI123" }), {
    collection: "legal_kali_articles",
    documentId: "KALIARTI123",
    ttlMs: KALI_DOCUMENT_TTL_MS,
  });
  assert.deepEqual(cacheSpec("/consult/kaliText", { id: "KALITEXT456" }), {
    collection: "legal_kali_texts",
    documentId: "KALITEXT456",
    ttlMs: KALI_DOCUMENT_TTL_MS,
  });
  assert.deepEqual(cacheSpec("/consult/kaliContIdcc", { id: "292" }), {
    collection: "legal_kali_search",
    documentId: "idcc_292",
    ttlMs: KALI_SEARCH_TTL_MS,
  });
  assert.equal(cacheSpec("/list/conventions", { pageNumber: 1, pageSize: 100 }).collection, "legal_kali_search");
});

test("premier appel utilise Légifrance puis écrit Firestore; deuxième appel vient de Firestore", async () => {
  const firestore = fakeFirestore();
  let officialCalls = 0;
  const nowMs = 1_800_000_000_000;
  const request = {
    db: firestore.db,
    path: "/consult/kaliArticle",
    body: { id: "KALIARTI123" },
    now: () => nowMs,
    logger: quietLogger,
    fetchOfficial: async () => {
      officialCalls += 1;
      return { id: "KALIARTI123", text: "officiel" };
    },
  };

  const first = await resolveWithLegalCache(request);
  const second = await resolveWithLegalCache(request);

  assert.deepEqual(first, { id: "KALIARTI123", text: "officiel" });
  assert.deepEqual(second, first);
  assert.equal(officialCalls, 1);
  assert.equal(firestore.calls.sets, 1);
  assert.equal(firestore.calls.gets, 2);

  const stored = firestore.store.get("legal_kali_articles/KALIARTI123");
  assert.equal(stored.schemaVersion, LEGAL_CACHE_SCHEMA_VERSION);
  assert.equal(stored.parserVersion, LEGAL_CACHE_PARSER_VERSION);
  assert.equal(stored.expiresAtMs, nowMs + KALI_DOCUMENT_TTL_MS);
});

test("cache expiré déclenche une nouvelle vérification officielle et remplace la valeur", async () => {
  const nowMs = 1_800_000_000_000;
  const key = "legal_kali_texts/KALITEXT456";
  const firestore = fakeFirestore(new Map([[
    key,
    {
      schemaVersion: LEGAL_CACHE_SCHEMA_VERSION,
      parserVersion: LEGAL_CACHE_PARSER_VERSION,
      expiresAtMs: nowMs - 1,
      payloadJson: JSON.stringify({ value: "périmée" }),
    },
  ]]));
  let officialCalls = 0;

  const result = await resolveWithLegalCache({
    db: firestore.db,
    path: "/consult/kaliText",
    body: { id: "KALITEXT456" },
    now: () => nowMs,
    logger: quietLogger,
    fetchOfficial: async () => {
      officialCalls += 1;
      return { value: "fraîche" };
    },
  });

  assert.deepEqual(result, { value: "fraîche" });
  assert.equal(officialCalls, 1);
  assert.equal(firestore.calls.sets, 1);
  assert.equal(JSON.parse(firestore.store.get(key).payloadJson).value, "fraîche");
});

test("erreur Firestore en lecture retombe sur Légifrance sans bloquer la réponse", async () => {
  const firestore = fakeFirestore(new Map(), { readError: true });
  let officialCalls = 0;

  const result = await resolveWithLegalCache({
    db: firestore.db,
    path: "/consult/kaliContIdcc",
    body: { id: "292" },
    now: () => 1_800_000_000_000,
    logger: quietLogger,
    fetchOfficial: async () => {
      officialCalls += 1;
      return { idcc: "0292", source: "officielle" };
    },
  });

  assert.deepEqual(result, { idcc: "0292", source: "officielle" });
  assert.equal(officialCalls, 1);
});

test("une donnée périmée n'est jamais utilisée silencieusement si Légifrance échoue", async () => {
  const nowMs = 1_800_000_000_000;
  const firestore = fakeFirestore(new Map([[
    "legal_kali_search/idcc_292",
    {
      schemaVersion: LEGAL_CACHE_SCHEMA_VERSION,
      parserVersion: LEGAL_CACHE_PARSER_VERSION,
      expiresAtMs: nowMs - 1,
      payloadJson: JSON.stringify({ stale: true }),
    },
  ]]));

  await assert.rejects(
    resolveWithLegalCache({
      db: firestore.db,
      path: "/consult/kaliContIdcc",
      body: { id: "292" },
      now: () => nowMs,
      logger: quietLogger,
      fetchOfficial: async () => {
        throw new Error("legifrance-unavailable");
      },
    }),
    /legifrance-unavailable/
  );
  assert.equal(firestore.calls.sets, 0);
});

test("schemaVersion/parserVersion différents invalident le cache", async () => {
  const nowMs = 1_800_000_000_000;
  const firestore = fakeFirestore(new Map([[
    "legal_kali_articles/KALIARTI123",
    {
      schemaVersion: LEGAL_CACHE_SCHEMA_VERSION,
      parserVersion: LEGAL_CACHE_PARSER_VERSION + 1,
      expiresAtMs: nowMs + KALI_DOCUMENT_TTL_MS,
      payloadJson: JSON.stringify({ value: "ancienne-version" }),
    },
  ]]));
  let officialCalls = 0;

  const result = await resolveWithLegalCache({
    db: firestore.db,
    path: "/consult/kaliArticle",
    body: { id: "KALIARTI123" },
    now: () => nowMs,
    logger: quietLogger,
    fetchOfficial: async () => {
      officialCalls += 1;
      return { value: "nouvelle-version" };
    },
  });

  assert.deepEqual(result, { value: "nouvelle-version" });
  assert.equal(officialCalls, 1);
});
