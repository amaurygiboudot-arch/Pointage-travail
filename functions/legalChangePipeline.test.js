"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const {
  CHANGE_COLLECTION,
  REANALYSIS_COLLECTION,
  matterHintsForSource,
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
          return {
            name,
            id,
            async set(value) {
              docs.set(`${name}/${id}`, { ...value });
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
  assert.equal(event.autoApplyAllowed, false);
  assert.equal(job.status, "PENDING");
  assert.equal(job.requiresOfficialRevalidation, true);
  assert.equal(job.autoApplyAllowed, false);
  assert.ok(job.matterHints.includes("OVERTIME"));
  assert.equal("payloadJson" in event, false);
});

test("les identifiants sont déterministes pour éviter les doublons lors d'une nouvelle tentative", () => {
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
