"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const {
  LEGAL_CACHE_SCHEMA_VERSION,
  LEGAL_CACHE_PARSER_VERSION,
  KALI_DOCUMENT_TTL_MS,
  KALI_SEARCH_TTL_MS,
  KALI_RULE_SEARCH_TTL_MS,
  ACCO_DOCUMENT_TTL_MS,
  ACCO_SEARCH_TTL_MS,
  LEGI_DOCUMENT_TTL_MS,
  LEGI_SEARCH_TTL_MS,
  BOCC_DOCUMENT_TTL_MS,
  BOCC_SEARCH_TTL_MS,
  JORF_DOCUMENT_TTL_MS,
  JORF_FEED_TTL_MS,
  cacheSpec,
  watchSpec,
  requestScope,
  payloadHash,
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

test("configure KALI avec documents 30 jours, IDCC 7 jours et recherches de règles 1 jour", () => {
  assert.deepEqual(cacheSpec("/consult/kaliArticle", { id: "KALIARTI123" }), {
    sourceFamily: "KALI",
    collection: "legal_kali_articles",
    documentId: "KALIARTI123",
    ttlMs: KALI_DOCUMENT_TTL_MS,
  });
  assert.deepEqual(cacheSpec("/consult/kaliText", { id: "KALITEXT456" }), {
    sourceFamily: "KALI",
    collection: "legal_kali_texts",
    documentId: "KALITEXT456",
    ttlMs: KALI_DOCUMENT_TTL_MS,
  });
  assert.deepEqual(cacheSpec("/consult/kaliContIdcc", { id: "292" }), {
    sourceFamily: "KALI",
    collection: "legal_kali_search",
    documentId: "idcc_292",
    ttlMs: KALI_SEARCH_TTL_MS,
  });
  assert.equal(cacheSpec("/list/conventions", { pageNumber: 1, pageSize: 100 }).collection, "legal_kali_search");
  assert.equal(cacheSpec("/search", { fond: "KALI", recherche: { pageNumber: 1 } }).ttlMs, KALI_RULE_SEARCH_TTL_MS);
});

test("configure le cache officiel ACCO, LEGI, BOCC et JORF avec des TTL adaptés", () => {
  const accoSearch = cacheSpec("/search", { fond: "ACCO", recherche: { pageNumber: 1 } });
  assert.equal(accoSearch.sourceFamily, "ACCO");
  assert.equal(accoSearch.collection, "legal_acco_search");
  assert.equal(accoSearch.ttlMs, ACCO_SEARCH_TTL_MS);
  assert.deepEqual(cacheSpec("/consult/acco", { id: "ACCOTEXT123" }), {
    sourceFamily: "ACCO",
    collection: "legal_acco_texts",
    documentId: "ACCOTEXT123",
    ttlMs: ACCO_DOCUMENT_TTL_MS,
  });

  const legiSearch = cacheSpec("/search", { fond: "CODE_DATE", recherche: { pageNumber: 1 } });
  assert.equal(legiSearch.sourceFamily, "LEGI");
  assert.equal(legiSearch.collection, "legal_legi_search");
  assert.equal(legiSearch.ttlMs, LEGI_SEARCH_TTL_MS);
  assert.deepEqual(cacheSpec("/consult/getArticle", { id: "LEGIARTI123" }), {
    sourceFamily: "LEGI",
    collection: "legal_legi_articles",
    documentId: "LEGIARTI123",
    ttlMs: LEGI_DOCUMENT_TTL_MS,
  });
  assert.equal(cacheSpec("/consult/legiPart", { textId: "LEGITEXT456", date: 1800000000000 }).collection, "legal_legi_parts");

  const boccSearch = cacheSpec("/list/boccsAndTexts", { idcc: "292", pageNumber: 1 });
  assert.equal(boccSearch.sourceFamily, "BOCC");
  assert.equal(boccSearch.collection, "legal_bocc_search");
  assert.equal(boccSearch.ttlMs, BOCC_SEARCH_TTL_MS);
  assert.equal(cacheSpec("/consult/getBoccTextPdfMetadata", { id: "bocc.pdf" }).ttlMs, BOCC_DOCUMENT_TTL_MS);

  assert.deepEqual(cacheSpec("/consult/lastNJo", { nbElement: 5 }), {
    sourceFamily: "JORF",
    collection: "legal_jorf_feed",
    documentId: "last_5",
    ttlMs: JORF_FEED_TTL_MS,
  });
  assert.equal(cacheSpec("/consult/jorfCont", { id: "JORFCONT123", pageNumber: 1 }).ttlMs, JORF_DOCUMENT_TTL_MS);
  assert.equal(cacheSpec("/consult/jorf", { textCid: "JORFTEXT456" }).collection, "legal_jorf_documents");
  assert.equal(cacheSpec("/search", { fond: "UNKNOWN" }), null);
});

test("identifie le périmètre de veille IDCC, SIRET, LEGI et JORF", () => {
  const kaliBody = {
    fond: "KALI",
    recherche: {
      champs: [{ typeChamp: "IDCC", criteres: [{ valeur: "292" }] }],
    },
  };
  const accoBody = {
    fond: "ACCO",
    recherche: {
      filtres: [{ facette: "SIRET_RAISON_SOCIALE", valeurs: ["12345678901234"] }],
    },
  };

  assert.deepEqual(requestScope("/search", kaliBody), { scopeType: "IDCC", scopeValue: "0292" });
  assert.deepEqual(requestScope("/search", accoBody), { scopeType: "SIRET", scopeValue: "12345678901234" });
  assert.deepEqual(requestScope("/list/boccsAndTexts", { idcc: "292" }), { scopeType: "IDCC", scopeValue: "0292" });
  assert.deepEqual(requestScope("/search", { fond: "CODE_DATE" }), { scopeType: "CODE", scopeValue: "CODE_DU_TRAVAIL" });
  assert.deepEqual(requestScope("/consult/lastNJo", { nbElement: 5 }), { scopeType: "GLOBAL", scopeValue: "FRANCE" });
});

test("seules les recherches et flux de veille sont marqués pour rafraîchissement planifié", () => {
  assert.equal(watchSpec("/consult/kaliArticle", { id: "KALIARTI123" }), null);
  assert.equal(watchSpec("/consult/acco", { id: "ACCOTEXT123" }), null);

  const kaliWatch = watchSpec("/consult/kaliContIdcc", { id: "292" });
  assert.equal(kaliWatch.collection, "legal_kali_search");
  assert.equal(kaliWatch.scopeValue, "0292");

  const jorfWatch = watchSpec("/consult/lastNJo", { nbElement: 5 });
  assert.equal(jorfWatch.collection, "legal_jorf_feed");
  assert.equal(jorfWatch.ttlMs, JORF_FEED_TTL_MS);
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
  assert.equal(stored.sourceFamily, "KALI");
  assert.equal(stored.expiresAtMs, nowMs + KALI_DOCUMENT_TTL_MS);
  assert.equal(stored.payloadHash, payloadHash(stored.payloadJson));
  assert.equal(stored.watchEnabled, undefined);
});

test("une recherche surveillée stocke la requête normalisée et sa prochaine vérification", async () => {
  const firestore = fakeFirestore();
  const nowMs = 1_800_000_000_000;
  const body = {
    fond: "ACCO",
    recherche: {
      filtres: [{ facette: "SIRET_RAISON_SOCIALE", valeurs: ["12345678901234"] }],
      pageNumber: 1,
    },
  };

  await resolveWithLegalCache({
    db: firestore.db,
    path: "/search",
    body,
    now: () => nowMs,
    logger: quietLogger,
    fetchOfficial: async () => ({ results: [] }),
  });

  const spec = cacheSpec("/search", body);
  const stored = firestore.store.get(`${spec.collection}/${spec.documentId}`);
  assert.equal(stored.watchEnabled, true);
  assert.equal(stored.requestJson, JSON.stringify(body));
  assert.equal(stored.scopeType, "SIRET");
  assert.equal(stored.scopeValue, "12345678901234");
  assert.equal(stored.nextCheckAtMs, nowMs + ACCO_SEARCH_TTL_MS);
  assert.equal(stored.lastOfficialCheckAtMs, nowMs);
});

test("un changement officiel est détecté lors d'une nouvelle vérification sans inventer de règle", async () => {
  const nowMs = 1_800_000_000_000;
  const body = { id: "292" };
  const key = "legal_kali_search/idcc_292";
  const oldPayload = JSON.stringify({ title: "ancienne version" });
  const firestore = fakeFirestore(new Map([[
    key,
    {
      schemaVersion: LEGAL_CACHE_SCHEMA_VERSION,
      parserVersion: LEGAL_CACHE_PARSER_VERSION,
      sourceFamily: "KALI",
      path: "/consult/kaliContIdcc",
      expiresAtMs: nowMs - 1,
      payloadJson: oldPayload,
      payloadHash: payloadHash(oldPayload),
      changeCount: 2,
    },
  ]]));

  await resolveWithLegalCache({
    db: firestore.db,
    path: "/consult/kaliContIdcc",
    body,
    now: () => nowMs,
    logger: quietLogger,
    fetchOfficial: async () => ({ title: "nouvelle version" }),
  });

  const stored = firestore.store.get(key);
  assert.equal(stored.changeCount, 3);
  assert.equal(stored.changeDetectedAtMs, nowMs);
  assert.equal(stored.previousPayloadHash, payloadHash(oldPayload));
  assert.equal(stored.watchEnabled, true);
});

test("ACCO utilise le même résolveur commun et conserve sa famille de source", async () => {
  const firestore = fakeFirestore();
  let officialCalls = 0;
  const nowMs = 1_800_000_000_000;
  const request = {
    db: firestore.db,
    path: "/consult/acco",
    body: { id: "ACCOTEXT123" },
    now: () => nowMs,
    logger: quietLogger,
    fetchOfficial: async () => {
      officialCalls += 1;
      return { id: "ACCOTEXT123", content: "accord officiel" };
    },
  };

  await resolveWithLegalCache(request);
  await resolveWithLegalCache(request);

  assert.equal(officialCalls, 1);
  const stored = firestore.store.get("legal_acco_texts/ACCOTEXT123");
  assert.equal(stored.sourceFamily, "ACCO");
  assert.equal(stored.expiresAtMs, nowMs + ACCO_DOCUMENT_TTL_MS);
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
