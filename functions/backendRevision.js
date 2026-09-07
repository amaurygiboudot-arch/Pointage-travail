"use strict";

function cleanText(value, maxLength = 240) {
  return String(value ?? "").trim().slice(0, maxLength);
}

function normalizeBackendRevision(data) {
  const value = data && typeof data === "object" ? data : {};
  const revision = cleanText(value.revision, 80);
  if (!revision) {
    return {
      schemaVersion: 1,
      available: false,
      revision: "",
      deployedAtMs: 0,
      runId: "",
      title: "",
      message: "",
    };
  }
  return {
    schemaVersion: 1,
    available: true,
    revision,
    deployedAtMs: Number(value.deployedAtMs) || 0,
    runId: cleanText(value.runId, 80),
    title: cleanText(value.title, 120),
    message: cleanText(value.message, 400),
  };
}

async function readBackendRevision({ db }) {
  if (!db) return normalizeBackendRevision(null);
  const snapshot = await db.collection("system_status").doc("backend_deploy").get();
  return normalizeBackendRevision(snapshot?.exists ? snapshot.data() : null);
}

module.exports = {
  normalizeBackendRevision,
  readBackendRevision,
};
