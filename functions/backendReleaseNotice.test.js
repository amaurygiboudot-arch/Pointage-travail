"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const {
  BACKEND_UPDATE_TOPIC,
  buildBackendReleaseNotice,
  normalizeRevision,
} = require("./backendReleaseNotice");

test("construit une révision Firebase publique et un push FCM sans secret", () => {
  const notice = buildBackendReleaseNotice({
    revision: "ABCDEF1234567890",
    message: "ci(functions): deploy backend\nwith details",
    deployedAtMs: 1_788_777_000_000,
  });

  assert.equal(notice.document.revision, "abcdef1234567890");
  assert.equal(notice.document.deployedAtMs, 1_788_777_000_000);
  assert.equal("message" in notice.document, false);
  assert.equal(notice.message.topic, BACKEND_UPDATE_TOPIC);
  assert.equal(notice.message.data.kind, "backend_update");
  assert.equal(notice.message.data.revision, "abcdef1234567890");
  assert.equal(notice.message.android.priority, "high");
  assert.equal("token" in notice.message, false);
});

test("refuse une pseudo-révision qui pourrait injecter des données arbitraires", () => {
  assert.throws(() => normalizeRevision("main; rm -rf /"), /Invalid backend release revision/);
  assert.throws(() => buildBackendReleaseNotice({ revision: "xyz", deployedAtMs: 1 }), /Invalid backend release revision/);
});
