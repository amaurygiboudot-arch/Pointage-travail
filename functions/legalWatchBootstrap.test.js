"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const {
  LEGAL_CACHE_SCHEMA_VERSION,
  LEGAL_CACHE_PARSER_VERSION,
} = require("./legalKaliCache");
const {
  GLOBAL_WATCH_SEEDS,
  seedDocument,
  existingSeedPatch,
  ensureGlobalLegalWatchSeeds,
} = require("./legalWatchBootstrap");

function fakeDb(initial = null) {
  const state = initial ? { ...initial } : null;
  const writes = [];
  return {
    writes,
    get state() {
      return writes.length ? writes.reduce((acc, item) => ({ ...(acc || {}), ...item }), state ? { ...state } : {}) : state;
    },
    collection(name) {
      assert.equal(name, "legal_jorf_feed");
      return {
        doc(id) {
          assert.equal(id, "last_5");
          return {
            async get() {
              const current = writes.length
                ? writes.reduce((acc, item) => ({ ...(acc || {}), ...item }), state ? { ...state } : {})
                : state;
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

const quietLogger = { info() {}, warn() {} };

test("la veille globale JORF est amorcée même sans appel utilisateur", async () => {
  const nowMs = 1_800_000_000_000;
  const db = fakeDb();
  const summary = await ensureGlobalLegalWatchSeeds({ db, now: () => nowMs, logger: quietLogger });
  assert.deepEqual(summary, { created: 1, updated: 0, unchanged: 0, failed: 0 });
  assert.equal(db.state.schemaVersion, LEGAL_CACHE_SCHEMA_VERSION);
  assert.equal(db.state.parserVersion, LEGAL_CACHE_PARSER_VERSION);
  assert.equal(db.state.watchEnabled, true);
  assert.equal(db.state.path, "/consult/lastNJo");
  assert.equal(db.state.requestJson, JSON.stringify({ nbElement: 5 }));
  assert.equal(db.state.nextCheckAtMs, nowMs);
  assert.equal(db.state.scopeType, "GLOBAL");
  assert.equal(db.state.scopeValue, "FRANCE");
  assert.equal("payloadJson" in db.state, false);
});

test("un seed déjà sain n'est pas réécrit ni rendu artificiellement périmé", async () => {
  const nowMs = 1_800_000_000_000;
  const built = seedDocument(GLOBAL_WATCH_SEEDS[0], nowMs);
  const existing = {
    ...built.data,
    payloadJson: JSON.stringify({ containers: ["A"] }),
    expiresAtMs: nowMs + 99_999,
    nextCheckAtMs: nowMs + 99_999,
  };
  const db = fakeDb(existing);
  const summary = await ensureGlobalLegalWatchSeeds({ db, now: () => nowMs, logger: quietLogger });
  assert.deepEqual(summary, { created: 0, updated: 0, unchanged: 1, failed: 0 });
  assert.equal(db.writes.length, 0);
});

test("un ancien seed de version incompatible est expiré avant d'être réutilisé", () => {
  const nowMs = 1_800_000_000_000;
  const patch = existingSeedPatch({
    schemaVersion: 0,
    parserVersion: 0,
    payloadJson: JSON.stringify({ containers: ["OLD"] }),
    expiresAtMs: nowMs + 999_999,
  }, GLOBAL_WATCH_SEEDS[0], nowMs);

  assert.equal(patch.schemaVersion, LEGAL_CACHE_SCHEMA_VERSION);
  assert.equal(patch.parserVersion, LEGAL_CACHE_PARSER_VERSION);
  assert.equal(patch.expiresAtMs, 0);
  assert.equal(patch.nextCheckAtMs, nowMs);
});
