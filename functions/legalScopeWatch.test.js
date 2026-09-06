"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const {
  BOCC_CHECK_INTERVAL_MS,
  SCOPE_WATCH_COLLECTION,
  SCOPE_WATCH_SCHEMA_VERSION,
  rollingBoccBody,
  boccReferenceKey,
  extractBoccReferenceKeys,
  referenceHash,
  scopeWatchId,
  ensureDerivedLegalScopeWatches,
  refreshScopeWatchDocument,
} = require("./legalScopeWatch");

function fakeScopeDb(initial = null) {
  const state = initial ? { ...initial } : null;
  const writes = [];
  return {
    writes,
    current() {
      return writes.reduce((acc, item) => ({ ...(acc || {}), ...item }), state ? { ...state } : {});
    },
    collection(name) {
      assert.equal(name, SCOPE_WATCH_COLLECTION);
      return {
        doc(id) {
          assert.equal(id, "bocc_idcc_0292");
          return {
            async get() {
              const current = writes.length ? writes.reduce((acc, item) => ({ ...(acc || {}), ...item }), state ? { ...state } : {}) : state;
              return { exists: Boolean(current), data: () => ({ ...(current || {}) }) };
            },
            async set(value, options) {
              assert.equal(options?.merge, true);
              writes.push({ ...value });
            },
          };
        },
      };
    },
  };
}

function makeRefreshRef(initial) {
  const state = { ...initial };
  return {
    state,
    ref: {
      async set(value, options) {
        assert.equal(options?.merge, true);
        Object.assign(state, value);
      },
    },
  };
}

const quietLogger = { info() {}, warn() {} };

function payload(...fileNames) {
  return {
    results: [
      {
        globalBocc: { dateParution: "01/09/2026" },
        texts: fileNames.map((fileName, index) => ({
          fileName,
          title: `Texte ${index + 1}`,
          idMainBocc: `BOCC-${index + 1}`,
        })),
      },
    ],
  };
}

test("la requête BOCC glissante reste bornée, récente et centrée sur l'IDCC", () => {
  const nowMs = Date.UTC(2026, 8, 6, 12, 0, 0);
  const body = rollingBoccBody("IDCC 0292", nowMs);
  assert.equal(body.idcc, "292");
  assert.equal(body.pageNumber, 1);
  assert.equal(body.pageSize, 100);
  assert.equal(body.sortValue, "BOCC_SORT_DESC");
  assert.match(body.intervalPublication, /^\d{2}\/\d{2}\/2026 > 06\/09\/2026$/);
});

test("les références BOCC utilisent le nom de PDF officiel comme identité stable", () => {
  assert.equal(boccReferenceKey({ fileName: "BOC_20260017_0001_P000.PDF", title: "Ancien titre" }), "boc_20260017_0001_p000.pdf");
  assert.equal(boccReferenceKey({ fileName: "../../secret.pdf" }), "");

  const keys = extractBoccReferenceKeys({
    texts: [
      { fileName: "a.pdf", title: "A" },
      { fileName: "A.PDF", title: "Titre modifié" },
    ],
    results: [
      { texts: [{ fileName: "b.pdf", title: "B" }] },
    ],
  });
  assert.deepEqual(keys, ["a.pdf", "b.pdf"]);
});

test("une consultation KALI IDCC inscrit automatiquement la veille BOCC glissante du même IDCC", async () => {
  const nowMs = 1_800_000_000_000;
  const db = fakeScopeDb();
  const created = await ensureDerivedLegalScopeWatches({
    db,
    path: "/consult/kaliContIdcc",
    body: { id: 292 },
    now: () => nowMs,
    logger: quietLogger,
  });

  assert.equal(created, true);
  const state = db.current();
  assert.equal(state.schemaVersion, SCOPE_WATCH_SCHEMA_VERSION);
  assert.equal(state.sourceFamily, "BOCC");
  assert.equal(state.scopeType, "IDCC");
  assert.equal(state.scopeValue, "0292");
  assert.equal(state.watchType, "BOCC_ROLLING_IDCC");
  assert.equal(state.nextCheckAtMs, nowMs);
  assert.equal(state.autoApplyAllowed, false);
  assert.equal(scopeWatchId("BOCC", "IDCC", "0292"), "bocc_idcc_0292");
});

test("réinscrire un IDCC déjà surveillé ne décale pas sa prochaine vérification", async () => {
  const nowMs = 1_800_000_000_000;
  const future = nowMs + 99_999;
  const db = fakeScopeDb({
    schemaVersion: SCOPE_WATCH_SCHEMA_VERSION,
    sourceFamily: "BOCC",
    sourceRole: "CHANGE_SIGNAL",
    watchType: "BOCC_ROLLING_IDCC",
    scopeType: "IDCC",
    scopeValue: "0292",
    windowDays: 180,
    nextCheckAtMs: future,
    autoApplyAllowed: false,
  });

  const changed = await ensureDerivedLegalScopeWatches({
    db,
    path: "/consult/kaliContIdcc",
    body: { id: "0292" },
    now: () => nowMs,
    logger: quietLogger,
  });
  assert.equal(changed, false);
  assert.equal(db.writes.length, 0);
});

test("le premier balayage BOCC construit une baseline sans créer de faux événement", async () => {
  const nowMs = 1_800_000_000_000;
  const holder = makeRefreshRef({
    schemaVersion: SCOPE_WATCH_SCHEMA_VERSION,
    sourceFamily: "BOCC",
    watchType: "BOCC_ROLLING_IDCC",
    scopeType: "IDCC",
    scopeValue: "0292",
    windowDays: 180,
  });
  let changes = 0;

  const result = await refreshScopeWatchDocument({
    documentId: "bocc_idcc_0292",
    ref: holder.ref,
    data: { ...holder.state },
    fetchOfficial: async (path, body) => {
      assert.equal(path, "/list/boccsAndTexts");
      assert.equal(body.idcc, "292");
      return payload("a.pdf");
    },
    onChange: async () => { changes += 1; },
    now: () => nowMs,
    logger: quietLogger,
  });

  assert.equal(result.changed, false);
  assert.equal(changes, 0);
  assert.deepEqual(holder.state.referenceKeys, ["a.pdf"]);
  assert.equal(holder.state.referenceHash, referenceHash(["a.pdf"]));
  assert.equal(holder.state.nextCheckAtMs, nowMs + BOCC_CHECK_INTERVAL_MS);
});

test("un nouveau PDF BOCC crée un signal pour l'IDCC, sans appliquer de règle", async () => {
  const firstSeen = ["a.pdf"];
  const nowMs = 1_800_000_000_000;
  const holder = makeRefreshRef({
    schemaVersion: SCOPE_WATCH_SCHEMA_VERSION,
    sourceFamily: "BOCC",
    watchType: "BOCC_ROLLING_IDCC",
    scopeType: "IDCC",
    scopeValue: "0292",
    windowDays: 180,
    referenceKeys: firstSeen,
    referenceHash: referenceHash(firstSeen),
  });
  let change = null;

  const result = await refreshScopeWatchDocument({
    documentId: "bocc_idcc_0292",
    ref: holder.ref,
    data: { ...holder.state },
    fetchOfficial: async () => payload("a.pdf", "b.pdf"),
    onChange: async (value) => { change = value; },
    now: () => nowMs,
    logger: quietLogger,
  });

  assert.equal(result.changed, true);
  assert.equal(result.added, 1);
  assert.equal(change.sourceFamily, "BOCC");
  assert.equal(change.scopeType, "IDCC");
  assert.equal(change.scopeValue, "0292");
  assert.equal(change.addedReferenceCount, 1);
  assert.deepEqual(holder.state.referenceKeys, ["a.pdf", "b.pdf"]);
});

test("une référence déjà vue peut disparaître puis réapparaître sans fausse alerte", async () => {
  const seen = ["a.pdf", "b.pdf"];
  const nowMs = 1_800_000_000_000;
  const holder = makeRefreshRef({
    schemaVersion: SCOPE_WATCH_SCHEMA_VERSION,
    sourceFamily: "BOCC",
    watchType: "BOCC_ROLLING_IDCC",
    scopeType: "IDCC",
    scopeValue: "0292",
    windowDays: 180,
    referenceKeys: seen,
    referenceHash: referenceHash(seen),
  });
  let changes = 0;

  const first = await refreshScopeWatchDocument({
    documentId: "bocc_idcc_0292",
    ref: holder.ref,
    data: { ...holder.state },
    fetchOfficial: async () => payload("b.pdf"),
    onChange: async () => { changes += 1; },
    now: () => nowMs,
    logger: quietLogger,
  });
  assert.equal(first.changed, false);
  assert.deepEqual(holder.state.referenceKeys, seen);

  const second = await refreshScopeWatchDocument({
    documentId: "bocc_idcc_0292",
    ref: holder.ref,
    data: { ...holder.state },
    fetchOfficial: async () => payload("a.pdf", "b.pdf"),
    onChange: async () => { changes += 1; },
    now: () => nowMs + BOCC_CHECK_INTERVAL_MS,
    logger: quietLogger,
  });
  assert.equal(second.changed, false);
  assert.equal(changes, 0);
});
