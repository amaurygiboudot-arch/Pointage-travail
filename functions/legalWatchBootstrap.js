"use strict";

const {
  LEGAL_CACHE_SCHEMA_VERSION,
  LEGAL_CACHE_PARSER_VERSION,
  watchSpec,
} = require("./legalKaliCache");

const GLOBAL_WATCH_SEEDS = Object.freeze([
  Object.freeze({ path: "/consult/lastNJo", body: Object.freeze({ nbElement: 5 }) }),
]);

function log(logger, level, message, details) {
  const fn = logger?.[level];
  if (typeof fn === "function") fn.call(logger, message, details);
}

function seedDocument(seed, nowMs) {
  const watch = watchSpec(seed.path, seed.body);
  if (!watch) return null;
  return {
    watch,
    data: {
      schemaVersion: LEGAL_CACHE_SCHEMA_VERSION,
      parserVersion: LEGAL_CACHE_PARSER_VERSION,
      source: "legifrance-piste",
      sourceFamily: watch.sourceFamily,
      path: seed.path,
      watchEnabled: true,
      requestJson: JSON.stringify(seed.body),
      nextCheckAtMs: nowMs,
      ...(watch.scopeType ? { scopeType: watch.scopeType } : {}),
      ...(watch.scopeValue ? { scopeValue: watch.scopeValue } : {}),
    },
  };
}

function existingSeedPatch(existing, seed, nowMs) {
  const built = seedDocument(seed, nowMs);
  if (!built) return null;
  const patch = {};
  const versionMismatch =
    existing?.schemaVersion !== LEGAL_CACHE_SCHEMA_VERSION ||
    existing?.parserVersion !== LEGAL_CACHE_PARSER_VERSION;

  if (versionMismatch) {
    patch.schemaVersion = LEGAL_CACHE_SCHEMA_VERSION;
    patch.parserVersion = LEGAL_CACHE_PARSER_VERSION;
    patch.expiresAtMs = 0;
    patch.nextCheckAtMs = nowMs;
  }
  if (existing?.watchEnabled !== true) patch.watchEnabled = true;
  if (existing?.requestJson !== built.data.requestJson) patch.requestJson = built.data.requestJson;
  if (!Number.isFinite(existing?.nextCheckAtMs)) patch.nextCheckAtMs = nowMs;
  if (existing?.source !== "legifrance-piste") patch.source = "legifrance-piste";
  if (existing?.sourceFamily !== built.data.sourceFamily) patch.sourceFamily = built.data.sourceFamily;
  if (existing?.path !== seed.path) patch.path = seed.path;
  if (built.data.scopeType && existing?.scopeType !== built.data.scopeType) patch.scopeType = built.data.scopeType;
  if (built.data.scopeValue && existing?.scopeValue !== built.data.scopeValue) patch.scopeValue = built.data.scopeValue;

  return Object.keys(patch).length ? patch : null;
}

async function ensureGlobalLegalWatchSeeds({
  db,
  now = () => Date.now(),
  logger = console,
}) {
  if (!db) return { created: 0, updated: 0, unchanged: 0, failed: 0 };
  const nowMs = Number(now());
  const summary = { created: 0, updated: 0, unchanged: 0, failed: 0 };

  for (const seed of GLOBAL_WATCH_SEEDS) {
    const built = seedDocument(seed, nowMs);
    if (!built) continue;
    try {
      const ref = db.collection(built.watch.collection).doc(built.watch.documentId);
      const snapshot = await ref.get();
      if (!snapshot?.exists) {
        await ref.set(built.data, { merge: true });
        summary.created += 1;
        log(logger, "info", "Global legal watch seed created", {
          sourceFamily: built.watch.sourceFamily,
          collection: built.watch.collection,
          documentId: built.watch.documentId,
        });
        continue;
      }

      const patch = existingSeedPatch(snapshot.data(), seed, nowMs);
      if (!patch) {
        summary.unchanged += 1;
        continue;
      }
      await ref.set(patch, { merge: true });
      summary.updated += 1;
    } catch (error) {
      summary.failed += 1;
      log(logger, "warn", "Global legal watch seed failed", {
        path: seed.path,
        error: String(error?.message || error || "unknown"),
      });
    }
  }

  return summary;
}

module.exports = {
  GLOBAL_WATCH_SEEDS,
  seedDocument,
  existingSeedPatch,
  ensureGlobalLegalWatchSeeds,
};
