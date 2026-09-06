"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const {
  normalizePlanRequest,
  analysisKindsForJob,
  safeReadyJob,
  listReadyLegalReanalysis,
} = require("./legalReanalysisPlan");

function fakeDb(seed = {}) {
  const collections = new Map();
  for (const [collection, docs] of Object.entries(seed)) {
    collections.set(collection, Object.entries(docs || {}).map(([id, data]) => ({
      id,
      data: () => ({ ...data }),
    })));
  }
  return {
    collection(name) {
      const docs = collections.get(name) || [];
      return {
        where(field, operator, expected) {
          assert.equal(operator, "==");
          const filtered = docs.filter((doc) => doc.data()?.[field] === expected);
          return {
            limit(limitValue) {
              return { async get() { return { docs: filtered.slice(0, limitValue) }; } };
            },
          };
        },
      };
    },
  };
}

test("normalise uniquement un IDCC et un SIRET valides", () => {
  assert.deepEqual(normalizePlanRequest({ idcc: "IDCC 292", siret: "123 456 789 01234" }), {
    idcc: "0292",
    siret: "12345678901234",
  });
  assert.deepEqual(normalizePlanRequest({ idcc: "0", siret: "123" }), { idcc: "", siret: "" });
});

test("déduit seulement les analyseurs sûrs ou les extractions candidates sans auto-application", () => {
  assert.deepEqual(analysisKindsForJob({
    sourceFamily: "KALI",
    targetSourceFamilies: ["KALI", "LEGI"],
    matterHints: ["OVERTIME", "NIGHT_WORK"],
  }), ["KALI_OVERTIME"]);
  assert.deepEqual(analysisKindsForJob({
    sourceFamily: "BOCC",
    targetSourceFamilies: ["KALI"],
    matterHints: ["OVERTIME"],
  }), ["KALI_OVERTIME"]);
  assert.deepEqual(analysisKindsForJob({ sourceFamily: "JORF", targetSourceFamilies: ["LEGI"] }), ["LEGI_ALL"]);
  assert.deepEqual(analysisKindsForJob({ sourceFamily: "ACCO" }), ["ACCO_EXTRACT_CANDIDATES"]);
});

test("ne renvoie jamais les payloads, hashes ou une permission d'auto-application", () => {
  const safe = safeReadyJob("job1", {
    status: "READY_FOR_ANALYSIS",
    sourceFamily: "ACCO",
    scopeType: "SIRET",
    scopeValue: "12345678901234",
    matterHints: ["BONUSES_INDEMNITIES"],
    targetSourceFamilies: ["ACCO"],
    payloadJson: "secret-payload",
    currentPayloadHash: "hash",
    autoApplyAllowed: true,
    lastQueuedAtMs: 1234,
  });
  assert.equal(safe.autoApplyAllowed, false);
  assert.deepEqual(safe.analysisKinds, ["ACCO_EXTRACT_CANDIDATES"]);
  assert.equal(safe.revisionKey, "job1:1234");
  assert.equal("payloadJson" in safe, false);
  assert.equal("currentPayloadHash" in safe, false);
  assert.equal(safeReadyJob("job2", { status: "PENDING", sourceFamily: "KALI" }), null);
});

test("liste uniquement les jobs READY correspondant à l'IDCC, au SIRET ou au national", async () => {
  const db = fakeDb({
    legal_reanalysis_jobs: {
      idccReady: {
        status: "READY_FOR_ANALYSIS",
        sourceFamily: "BOCC",
        scopeType: "IDCC",
        scopeValue: "0292",
        matterHints: ["OVERTIME"],
        targetSourceFamilies: ["KALI"],
        lastQueuedAtMs: 400,
      },
      idccOther: {
        status: "READY_FOR_ANALYSIS",
        sourceFamily: "KALI",
        scopeType: "IDCC",
        scopeValue: "2148",
        matterHints: ["OVERTIME"],
        targetSourceFamilies: ["KALI"],
        lastQueuedAtMs: 500,
      },
      siretReady: {
        status: "READY_FOR_ANALYSIS",
        sourceFamily: "ACCO",
        scopeType: "SIRET",
        scopeValue: "12345678901234",
        matterHints: ["BONUSES_INDEMNITIES"],
        targetSourceFamilies: ["ACCO"],
        lastQueuedAtMs: 300,
      },
      nationalReady: {
        status: "READY_FOR_ANALYSIS",
        sourceFamily: "JORF",
        scopeType: "GLOBAL",
        scopeValue: "FRANCE",
        matterHints: ["PAYROLL_LEGAL_BASE"],
        targetSourceFamilies: ["LEGI"],
        lastQueuedAtMs: 600,
      },
      codeReady: {
        status: "READY_FOR_ANALYSIS",
        sourceFamily: "LEGI",
        scopeType: "CODE",
        scopeValue: "CODE_DU_TRAVAIL",
        matterHints: ["OVERTIME"],
        targetSourceFamilies: ["LEGI"],
        lastQueuedAtMs: 200,
      },
      pending: {
        status: "REVALIDATION_QUEUED",
        sourceFamily: "KALI",
        scopeType: "IDCC",
        scopeValue: "0292",
        lastQueuedAtMs: 999,
      },
    },
  });

  const jobs = await listReadyLegalReanalysis({
    db,
    idcc: "292",
    siret: "12345678901234",
  });
  assert.deepEqual(jobs.map((job) => job.jobId), ["nationalReady", "idccReady", "siretReady", "codeReady"]);
  assert.equal(jobs.find((job) => job.jobId === "siretReady")?.analysisKinds[0], "ACCO_EXTRACT_CANDIDATES");
  assert.equal(jobs.some((job) => job.jobId === "idccOther"), false);
  assert.equal(jobs.some((job) => job.jobId === "pending"), false);
});
