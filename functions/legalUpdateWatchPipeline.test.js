"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const {
  LEGAL_CACHE_SCHEMA_VERSION,
  LEGAL_CACHE_PARSER_VERSION,
  cacheSpec,
  payloadHash,
} = require("./legalKaliCache");
const { refreshWatchDocument } = require("./legalUpdateWatch");

function makeDoc(id, data) {
  const state = { ...data };
  let replacements = 0;
  return {
    id,
    state,
    get replacements() {
      return replacements;
    },
    ref: {
      async set(value, options) {
        if (options?.merge) {
          Object.assign(state, value);
          return;
        }
        replacements += 1;
        Object.keys(state).forEach((key) => delete state[key]);
        Object.assign(state, value);
      },
    },
  };
}

const quietLogger = { info() {}, warn() {} };

test("l'événement est enregistré avant le remplacement du cache officiel", async () => {
  const nowMs = 1_800_000_000_000;
  const body = { nbElement: 5 };
  const spec = cacheSpec("/consult/lastNJo", body);
  const oldJson = JSON.stringify({ containers: ["A"] });
  const doc = makeDoc(spec.documentId, {
    schemaVersion: LEGAL_CACHE_SCHEMA_VERSION,
    parserVersion: LEGAL_CACHE_PARSER_VERSION,
    watchEnabled: true,
    sourceFamily: "JORF",
    path: "/consult/lastNJo",
    requestJson: JSON.stringify(body),
    nextCheckAtMs: nowMs - 1,
    expiresAtMs: nowMs - 1,
    payloadJson: oldJson,
    payloadHash: payloadHash(oldJson),
    scopeType: "GLOBAL",
    scopeValue: "FRANCE",
  });

  let callbackSawOldCache = false;
  const result = await refreshWatchDocument({
    collection: spec.collection,
    documentId: spec.documentId,
    ref: doc.ref,
    data: { ...doc.state },
    fetchOfficial: async () => ({ containers: ["A", "B"] }),
    onChange: async (change) => {
      callbackSawOldCache = JSON.parse(doc.state.payloadJson).containers.length === 1;
      assert.equal(change.scopeValue, "FRANCE");
      assert.notEqual(change.previousPayloadHash, change.currentPayloadHash);
    },
    now: () => nowMs,
    logger: quietLogger,
  });

  assert.equal(result.changed, true);
  assert.equal(callbackSawOldCache, true);
  assert.equal(doc.replacements, 1);
  assert.equal(JSON.parse(doc.state.payloadJson).containers.length, 2);
});

test("si la file de changement échoue, l'ancien cache reste intact pour permettre une nouvelle tentative", async () => {
  const nowMs = 1_800_000_000_000;
  const body = { nbElement: 5 };
  const spec = cacheSpec("/consult/lastNJo", body);
  const oldJson = JSON.stringify({ containers: ["A"] });
  const doc = makeDoc(spec.documentId, {
    schemaVersion: LEGAL_CACHE_SCHEMA_VERSION,
    parserVersion: LEGAL_CACHE_PARSER_VERSION,
    watchEnabled: true,
    path: "/consult/lastNJo",
    requestJson: JSON.stringify(body),
    payloadJson: oldJson,
    payloadHash: payloadHash(oldJson),
    scopeType: "GLOBAL",
    scopeValue: "FRANCE",
  });

  await assert.rejects(() => refreshWatchDocument({
    collection: spec.collection,
    documentId: spec.documentId,
    ref: doc.ref,
    data: { ...doc.state },
    fetchOfficial: async () => ({ containers: ["A", "B"] }),
    onChange: async () => {
      throw new Error("queue-offline");
    },
    now: () => nowMs,
    logger: quietLogger,
  }), /queue-offline/);

  assert.equal(doc.replacements, 0);
  assert.equal(doc.state.payloadJson, oldJson);
});
