"use strict";

const { watchSpec } = require("./legalKaliCache");

function log(logger, level, message, details) {
  const fn = logger?.[level];
  if (typeof fn === "function") fn.call(logger, message, details);
}

function registrationPatch({ data, watch, body, nowMs }) {
  if (!data || typeof data.payloadJson !== "string") return null;

  const patch = {};
  if (data.watchEnabled !== true) patch.watchEnabled = true;
  if (typeof data.requestJson !== "string" || !data.requestJson) {
    patch.requestJson = JSON.stringify(body ?? {});
  }
  if (!Number.isFinite(data.nextCheckAtMs)) {
    const expiresAtMs = Number(data.expiresAtMs);
    patch.nextCheckAtMs = Number.isFinite(expiresAtMs)
      ? Math.max(nowMs, expiresAtMs)
      : nowMs + watch.ttlMs;
  }
  if (watch.scopeType && data.scopeType !== watch.scopeType) {
    patch.scopeType = watch.scopeType;
  }
  if (watch.scopeValue && data.scopeValue !== watch.scopeValue) {
    patch.scopeValue = watch.scopeValue;
  }

  return Object.keys(patch).length ? patch : null;
}

async function ensureLegalWatchRegistration({
  db,
  path,
  body,
  now = () => Date.now(),
  logger = console,
}) {
  if (!db) return false;
  const watch = watchSpec(path, body);
  if (!watch) return false;

  try {
    const ref = db.collection(watch.collection).doc(watch.documentId);
    const snapshot = await ref.get();
    if (!snapshot?.exists) return false;

    const data = snapshot.data();
    const nowMs = Number(now());
    const patch = registrationPatch({ data, watch, body, nowMs });
    if (!patch) return false;

    await ref.set(patch, { merge: true });
    log(logger, "info", `Legal ${watch.sourceFamily} watch registration backfilled`, {
      path,
      collection: watch.collection,
      documentId: watch.documentId,
      scopeType: patch.scopeType || data?.scopeType || null,
      scopeValue: patch.scopeValue || data?.scopeValue || null,
    });
    return true;
  } catch (error) {
    log(logger, "warn", "Legal watch registration backfill failed", {
      path,
      error: String(error?.message || error || "unknown"),
    });
    return false;
  }
}

module.exports = {
  registrationPatch,
  ensureLegalWatchRegistration,
};
