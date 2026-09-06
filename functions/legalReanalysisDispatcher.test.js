"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const {
  dispatchPendingLegalReanalysis,
  targetScope,
} = require("./legalReanalysisDispatcher");

function makeDb(seed = {}) {
  const state = new Map();
  for (const [collection, docs] of Object.entries(seed)) {
    for (const [id, value] of Object.entries(docs || {})) {
      state.set(`${collection}/${id}`, { ...value });
    }
  }

  function docRef(collection, id) {
    const key = `${collection}/${id}`;
    return {
      id,
      async get() {
        const value = state.get(key);
        return { exists: Boolean(value), data: () => (value ? { ...value } : undefined) };
      },
      async set(value, options) {
        state.set(key, options?.merge ? { ...(state.get(key) || {}), ...value } : { ...value });
      },
    };
  }

  function docsFor(collection) {
    const prefix = `${collection}/`;
    return [...state.entries()]
      .filter(([key]) => key.startsWith(prefix))
      .map(([key, value]) => {
        const id = key.slice(prefix.length);
        return { id, ref: docRef(collection, id), data: () => ({ ...value }) };
      });
  }

  return {
    state,
    collection(collection) {
      return {
        doc(id) {
          return docRef(collection, id);
        },
        where(field, operator, expected) {
          assert.equal(operator, "==");
          const filtered = docsFor(collection).filter((doc) => doc.data()?.[field] === expected);
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

const quietLogger = { info() {}, warn() {} };

test("résout des cibles bornées sans inventer de périmètre KALI global", () => {
  assert.deepEqual(targetScope({ scopeType: "IDCC", scopeValue: "292" }, "KALI"), {
    scopeType: "IDCC",
    scopeValue: "0292",
  });
  assert.deepEqual(targetScope({ scopeType: "GLOBAL", scopeValue: "FRANCE" }, "LEGI"), {
    scopeType: "CODE",
    scopeValue: "CODE_DU_TRAVAIL",
  });
  assert.equal(targetScope({ scopeType: "GLOBAL", scopeValue: "FRANCE" }, "KALI"), null);
});

test("un changement KALI ne rappelle pas KALI mais accélère son recoupement LEGI", async () => {
  const nowMs = 1_800_000_000_000;
  const db = makeDb({
    legal_reanalysis_jobs: {
      job1: {
        status: "PENDING",
        sourceFamily: "KALI",
        scopeType: "IDCC",
        scopeValue: "0292",
        targetSourceFamilies: ["KALI", "LEGI"],
        revalidationTargets: [
          { sourceFamily: "KALI", reason: "DIRECT_SOURCE_CHANGE" },
          { sourceFamily: "LEGI", reason: "CROSS_CHECK_LEGAL_BASE" },
        ],
        triggerEventId: "evt1",
      },
    },
    legal_change_events: { evt1: { status: "DETECTED" } },
    legal_kali_search: {
      idcc_292: { watchEnabled: true, scopeType: "IDCC", scopeValue: "0292", expiresAtMs: nowMs + 99_999 },
    },
    legal_legi_search: {
      search_law: { watchEnabled: true, scopeType: "CODE", scopeValue: "CODE_DU_TRAVAIL", expiresAtMs: nowMs + 99_999 },
    },
  });

  const summary = await dispatchPendingLegalReanalysis({ db, now: () => nowMs, logger: quietLogger });
  assert.equal(summary.dispatched, 1);
  assert.equal(summary.queuedWatches, 1);

  const job = db.state.get("legal_reanalysis_jobs/job1");
  assert.equal(job.status, "REVALIDATION_QUEUED");
  assert.deepEqual(job.readySourceFamilies, ["KALI"]);
  assert.deepEqual(job.unresolvedTargetSourceFamilies, []);
  assert.equal(db.state.get("legal_kali_search/idcc_292").nextCheckAtMs, undefined);
  assert.equal(db.state.get("legal_legi_search/search_law").nextCheckAtMs, nowMs);
});

test("un changement ACCO direct devient prêt à analyser sans appel officiel redondant", async () => {
  const nowMs = 1_800_000_000_000;
  const db = makeDb({
    legal_reanalysis_jobs: {
      jobAcco: {
        status: "PENDING",
        sourceFamily: "ACCO",
        scopeType: "SIRET",
        scopeValue: "12345678901234",
        targetSourceFamilies: ["ACCO"],
        revalidationTargets: [{ sourceFamily: "ACCO", reason: "DIRECT_SOURCE_CHANGE" }],
      },
    },
    legal_acco_search: {
      acco1: { watchEnabled: true, scopeType: "SIRET", scopeValue: "12345678901234", expiresAtMs: nowMs + 9_999 },
    },
  });

  const summary = await dispatchPendingLegalReanalysis({ db, now: () => nowMs, logger: quietLogger });
  assert.equal(summary.readyForAnalysis, 1);
  assert.equal(summary.queuedWatches, 0);
  assert.equal(db.state.get("legal_reanalysis_jobs/jobAcco").status, "READY_FOR_ANALYSIS");
  assert.equal(db.state.get("legal_acco_search/acco1").nextCheckAtMs, undefined);
});

test("un signal JORF global accélère LEGI mais laisse KALI en WAITING_SCOPE", async () => {
  const nowMs = 1_800_000_000_000;
  const db = makeDb({
    legal_reanalysis_jobs: {
      jobJorf: {
        status: "PENDING",
        sourceFamily: "JORF",
        scopeType: "GLOBAL",
        scopeValue: "FRANCE",
        targetSourceFamilies: ["LEGI", "KALI"],
        revalidationTargets: [
          { sourceFamily: "LEGI", reason: "RECHECK_CONSOLIDATED_LAW" },
          { sourceFamily: "KALI", reason: "CHECK_CONVENTION_EXTENSION_IMPACT" },
        ],
        triggerEventId: "evtJorf",
      },
    },
    legal_change_events: { evtJorf: { status: "DETECTED" } },
    legal_legi_search: {
      law1: { watchEnabled: true, scopeType: "CODE", scopeValue: "CODE_DU_TRAVAIL", expiresAtMs: nowMs + 1_000 },
    },
    legal_kali_search: {
      kali292: { watchEnabled: true, scopeType: "IDCC", scopeValue: "0292", expiresAtMs: nowMs + 1_000 },
    },
  });

  const summary = await dispatchPendingLegalReanalysis({ db, now: () => nowMs, logger: quietLogger });
  assert.equal(summary.waitingScope, 1);
  assert.equal(summary.queuedWatches, 1);

  const job = db.state.get("legal_reanalysis_jobs/jobJorf");
  assert.equal(job.status, "WAITING_SCOPE");
  assert.deepEqual(job.unresolvedTargetSourceFamilies, ["KALI"]);
  assert.equal(db.state.get("legal_legi_search/law1").nextCheckAtMs, nowMs);
  assert.equal(db.state.get("legal_kali_search/kali292").nextCheckAtMs, undefined);
});
