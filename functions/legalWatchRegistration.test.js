"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { cacheSpec } = require("./legalKaliCache");
const { ensureLegalWatchRegistration } = require("./legalWatchRegistration");

function fakeDoc(initial, exists = true) {
  const state = { ...initial };
  let writes = 0;
  return {
    state,
    get writes() {
      return writes;
    },
    ref: {
      async get() {
        return { exists, data: () => ({ ...state }) };
      },
      async set(value, options) {
        assert.equal(options?.merge, true);
        Object.assign(state, value);
        writes += 1;
      },
    },
  };
}

function fakeDb(doc) {
  return {
    collection() {
      return {
        doc() {
          return doc.ref;
        },
      };
    },
  };
}

const quietLogger = { info() {}, warn() {} };

test("backfill ajoute la veille à un ancien cache sans rappeler Légifrance", async () => {
  const nowMs = 1_800_000_000_000;
  const body = { id: 292 };
  const spec = cacheSpec("/consult/kaliContIdcc", body);
  const expiresAtMs = nowMs + 20_000;
  const doc = fakeDoc({
    payloadJson: JSON.stringify({ id: "KALICONT000005635856" }),
    expiresAtMs,
  });

  const changed = await ensureLegalWatchRegistration({
    db: fakeDb(doc),
    path: "/consult/kaliContIdcc",
    body,
    now: () => nowMs,
    logger: quietLogger,
  });

  assert.equal(changed, true);
  assert.equal(doc.writes, 1);
  assert.equal(doc.state.watchEnabled, true);
  assert.equal(doc.state.requestJson, JSON.stringify(body));
  assert.equal(doc.state.nextCheckAtMs, expiresAtMs);
  assert.equal(doc.state.scopeType, "IDCC");
  assert.equal(doc.state.scopeValue, "0292");
  assert.equal(spec.collection, "legal_kali_search");
});

test("un cache déjà inscrit à la veille n'est pas réécrit", async () => {
  const nowMs = 1_800_000_000_000;
  const body = { nbElement: 5 };
  const doc = fakeDoc({
    payloadJson: JSON.stringify({ containers: [] }),
    watchEnabled: true,
    requestJson: JSON.stringify(body),
    nextCheckAtMs: nowMs + 10_000,
    scopeType: "GLOBAL",
    scopeValue: "FRANCE",
  });

  const changed = await ensureLegalWatchRegistration({
    db: fakeDb(doc),
    path: "/consult/lastNJo",
    body,
    now: () => nowMs,
    logger: quietLogger,
  });

  assert.equal(changed, false);
  assert.equal(doc.writes, 0);
});

test("un document absent ou sans réponse officielle n'est jamais créé comme cache partiel", async () => {
  const body = { nbElement: 5 };
  const absent = fakeDoc({}, false);
  const empty = fakeDoc({});

  assert.equal(await ensureLegalWatchRegistration({
    db: fakeDb(absent),
    path: "/consult/lastNJo",
    body,
    logger: quietLogger,
  }), false);
  assert.equal(absent.writes, 0);

  assert.equal(await ensureLegalWatchRegistration({
    db: fakeDb(empty),
    path: "/consult/lastNJo",
    body,
    logger: quietLogger,
  }), false);
  assert.equal(empty.writes, 0);
});
