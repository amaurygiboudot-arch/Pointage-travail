"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const {
  CHANGE_COLLECTION,
  REANALYSIS_COLLECTION,
  matterHintsForSource,
  sourceRole,
  revalidationTargetsForSource,
  buildChangeDocuments,
  recordLegalChangeAndQueue,
} = require("./legalChangePipeline");

function fakeDb() {
  const docs = new Map();
  return {
    docs,
    collection(name) {
      return {
        doc(id) {
          const key = `${name}/${id}`;
          return {
            async get() {
              const value = docs.get(key);
              return { exists: Boolean(value), data: () => ({ ...(value || {}) }) };
            },
            async set(value, options) {
              if (options?.merge && docs.has(key)) {
                docs.set(key, { ...docs.get(key), ...value });
              } else {
                docs.set(key, { ...value });
              }
            },
          };
        },
      };
    },
  };
}

const quietLogger = { info() {}, warn() {} };

test("un changement KALI crée un événement et une réanalyse sans autoriser l'application automatique", async () => {
  const db = fakeDb();
  const change = {
    sourceFamily: "KALI",
    path: "/search",
    collection: "legal_kali_search",
    documentId: "search_abc",
    scopeType: "IDCC",
    scopeValue: "0292",
    previousPayloadHash: "oldhash",
    currentPayloadHash: "newhash",
    detectedAtMs: 1_800_000_000_000,
  };

  const ids = await recordLegalChangeAndQueue({ db, change, logger: quietLogger });
  assert.ok(ids.eventId.startsWith("evt_"));
  assert.ok(ids.jobId.startsWith("job_"));

  const event = db.docs.get(`${CHANGE_COLLECTION}/${ids.eventId}`);
  const job = db.docs.get(`${REANALYSIS_COLLECTION}/${ids.jobId}`);
  assert.equal(event.status, "DETECTED");
  assert.equal(event.scopeValue, "0292");
  assert.equal(event.sourceRole, "CONSOLIDATED_OR_DIRECT_SOURCE");
  assert.equal(event.autoApplyAllowed, false);
  assert.equal(job.status, "PENDING");
  assert.equal(job.requiresOfficialRevalidation, true);
  assert.equal(job.autoApplyAllowed, false);
  assert.equal(job.coalescedSignal, false);
  assert.equal(job.requiresScopeResolution, false);
  assert.deepEqual(job.targetSourceFamilies, ["KALI", "LEGI"]);
  assert.ok(job.matterHints.includes("OVERTIME"));
  assert.equal("payloadJson" in event, false);
});

test("les identifiants d'événement sont déterministes pour éviter les doublons lors d'une nouvelle tentative", () => {
  const change = {
    sourceFamily: "JORF",
    path: "/consult/lastNJo",
    collection: "legal_jorf_feed",
    documentId: "last_5",
    scopeType: "GLOBAL",
    scopeValue: "FRANCE",
    previousPayloadHash: "a",
    currentPayloadHash: "b",
  };
  const first = buildChangeDocuments(change, 100);
  const second = buildChangeDocuments(change, 200);
  assert.equal(first.eventId, second.eventId);
  assert.equal(first.jobId, second.jobId);
});

test("aucun événement n'est créé sans véritable changement de hash", () => {
  assert.equal(buildChangeDocuments({
    sourceFamily: "LEGI",
    path: "/search",
    collection: "legal_legi_search",
    documentId: "x",
    previousPayloadHash: "same",
    currentPayloadHash: "same",
  }, 100), null);
});

test("les familles conventionnelles et nationales reçoivent des pistes de réanalyse adaptées", () => {
  assert.ok(matterHintsForSource("ACCO").includes("CLASSIFICATION"));
  assert.ok(matterHintsForSource("BOCC").includes("BONUSES_INDEMNITIES"));
  assert.ok(matterHintsForSource("JORF").includes("PAYROLL_LEGAL_BASE"));
});

test("BOCC est un signal de changement qui renvoie vers KALI consolidé", () => {
  const docs = buildChangeDocuments({
    sourceFamily: "BOCC",
    path: "/list/boccsAndTexts",
    collection: "legal_bocc_search",
    documentId: "list_x",
    scopeType: "IDCC",
    scopeValue: "0292",
    previousPayloadHash: "old",
    currentPayloadHash: "new",
  }, 100);

  assert.equal(sourceRole("BOCC"), "CHANGE_SIGNAL");
  assert.deepEqual(revalidationTargetsForSource("BOCC"), [
    { sourceFamily: "KALI", reason: "RECHECK_CONSOLIDATED_CONVENTION" },
  ]);
  assert.deepEqual(docs.job.targetSourceFamilies, ["KALI"]);
  assert.equal(docs.job.requiresScopeResolution, false);
  assert.equal(docs.job.coalescedSignal, true);
});

test("JORF reste un signal et exige résolution du périmètre avant contrôle LEGI/KALI", () => {
  const docs = buildChangeDocuments({
    sourceFamily: "JORF",
    path: "/consult/lastNJo",
    collection: "legal_jorf_feed",
    documentId: "last_5",
    scopeType: "GLOBAL",
    scopeValue: "FRANCE",
    previousPayloadHash: "old",
    currentPayloadHash: "new",
  }, 100);

  assert.equal(docs.event.sourceRole, "CHANGE_SIGNAL");
  assert.deepEqual(docs.job.targetSourceFamilies, ["LEGI", "KALI"]);
  assert.equal(docs.job.requiresScopeResolution, true);
  assert.equal(docs.job.autoApplyAllowed, false);
});

test("plusieurs évolutions JORF gardent des événements séparés mais un seul job global en attente", async () => {
  const db = fakeDb();
  const base = {
    sourceFamily: "JORF",
    path: "/consult/lastNJo",
    collection: "legal_jorf_feed",
    documentId: "last_5",
    scopeType: "GLOBAL",
    scopeValue: "FRANCE",
  };

  const first = await recordLegalChangeAndQueue({
    db,
    change: { ...base, previousPayloadHash: "a", currentPayloadHash: "b", detectedAtMs: 100 },
    logger: quietLogger,
  });
  const second = await recordLegalChangeAndQueue({
    db,
    change: { ...base, previousPayloadHash: "b", currentPayloadHash: "c", detectedAtMs: 200 },
    logger: quietLogger,
  });

  assert.notEqual(first.eventId, second.eventId);
  assert.equal(first.jobId, second.jobId);
  const job = db.docs.get(`${REANALYSIS_COLLECTION}/${first.jobId}`);
  assert.equal(job.createdAtMs, 100);
  assert.equal(job.lastQueuedAtMs, 200);
  assert.equal(job.coalescedEventCount, 2);
  assert.equal(job.previousTriggerEventId, first.eventId);
  assert.equal(job.latestTriggerEventId, second.eventId);
  assert.equal(job.status, "PENDING");
});
