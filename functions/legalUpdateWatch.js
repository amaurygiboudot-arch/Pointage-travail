"use strict";

const {
  LEGAL_CACHE_SCHEMA_VERSION,
  LEGAL_CACHE_PARSER_VERSION,
  cacheSpec,
  payloadHash,
  nextCacheDocument,
} = require("./legalKaliCache");

const WATCH_COLLECTIONS = Object.freeze([
  "legal_acco_search",
  "legal_kali_search",
  "legal_legi_search",
  "legal_bocc_search",
  "legal_jorf_feed",
]);

const DEFAULT_BATCH_SIZE = 12;

function parseWatchRequest(collection, documentId, data) {
  if (!data || data.watchEnabled !== true) return null;
  if (data.schemaVersion !== LEGAL_CACHE_SCHEMA_VERSION) return null;
  if (data.parserVersion !== LEGAL_CACHE_PARSER_VERSION) return null;
  if (typeof data.path !== "string" || !data.path) return null;
  if (typeof data.requestJson !== "string" || !data.requestJson) return null;

  let body;
  try {
    body = JSON.parse(data.requestJson);
  } catch (_error) {
    return null;
  }

  const spec = cacheSpec(data.path, body);
  if (!spec) return null;
  if (spec.collection !== collection || spec.documentId !== documentId) return null;

  return {
    path: data.path,
    body,
    spec,
  };
}

function log(logger, level, message, details) {
  const fn = logger?.[level];
  if (typeof fn === "function") fn.call(logger, message, details);
}

async function markRefreshFailure(ref, data, nowMs, error) {
  const previous = Number(data?.refreshFailureCount);
  const refreshFailureCount = Number.isFinite(previous) && previous >= 0 ? previous + 1 : 1;
  await ref.set({
    lastRefreshErrorAtMs: nowMs,
    refreshFailureCount,
    lastRefreshError: String(error?.message || error || "unknown").slice(0, 240),
  }, { merge: true });
}

async function refreshWatchDocument({
  collection,
  documentId,
  ref,
  data,
  fetchOfficial,
  onChange = null,
  now = () => Date.now(),
  logger = console,
}) {
  const request = parseWatchRequest(collection, documentId, data);
  if (!request) return { status: "skipped", changed: false };

  const officialPayload = await fetchOfficial(request.path, request.body);
  const payloadJson = JSON.stringify(officialPayload);
  if (typeof payloadJson !== "string") {
    return { status: "skipped", changed: false };
  }

  const checkedAtMs = Number(now());
  const watch = {
    ...request.spec,
    ...(typeof data.scopeType === "string" ? { scopeType: data.scopeType } : {}),
    ...(typeof data.scopeValue === "string" ? { scopeValue: data.scopeValue } : {}),
  };
  const { document, changed } = nextCacheDocument({
    previousData: data,
    spec: request.spec,
    watch,
    path: request.path,
    body: request.body,
    payloadJson,
    checkedAtMs,
  });

  document.refreshFailureCount = 0;
  document.lastRefreshErrorAtMs = null;
  document.lastRefreshError = null;

  if (changed && typeof onChange === "function") {
    await onChange({
      sourceFamily: request.spec.sourceFamily,
      path: request.path,
      collection,
      documentId,
      scopeType: document.scopeType || data?.scopeType || null,
      scopeValue: document.scopeValue || data?.scopeValue || null,
      previousPayloadHash: document.previousPayloadHash || data?.payloadHash || null,
      currentPayloadHash: document.payloadHash,
      detectedAtMs: checkedAtMs,
    });
  }

  await ref.set(document);
  log(logger, "info", `Legal ${request.spec.sourceFamily} watch refreshed`, {
    path: request.path,
    collection,
    documentId,
    changed,
  });

  return {
    status: "refreshed",
    changed,
    sourceFamily: request.spec.sourceFamily,
    payloadHash: payloadHash(payloadJson),
  };
}

async function refreshDueLegalWatches({
  db,
  fetchOfficial,
  onChange = null,
  now = () => Date.now(),
  logger = console,
  batchSize = DEFAULT_BATCH_SIZE,
}) {
  if (!db || typeof fetchOfficial !== "function") {
    return { scanned: 0, refreshed: 0, changed: 0, failed: 0, skipped: 0 };
  }

  const nowMs = Number(now());
  let remaining = Math.max(1, Math.min(50, Number.parseInt(batchSize, 10) || DEFAULT_BATCH_SIZE));
  const summary = { scanned: 0, refreshed: 0, changed: 0, failed: 0, skipped: 0 };

  for (const collection of WATCH_COLLECTIONS) {
    if (remaining <= 0) break;

    let snapshot;
    try {
      snapshot = await db.collection(collection)
        .where("nextCheckAtMs", "<=", nowMs)
        .orderBy("nextCheckAtMs", "asc")
        .limit(remaining)
        .get();
    } catch (error) {
      summary.failed += 1;
      log(logger, "warn", "Legal watch scan failed", {
        collection,
        error: String(error?.message || error || "unknown"),
      });
      continue;
    }

    const docs = Array.isArray(snapshot?.docs) ? snapshot.docs : [];
    for (const doc of docs) {
      if (remaining <= 0) break;
      remaining -= 1;
      summary.scanned += 1;
      const data = typeof doc.data === "function" ? doc.data() : null;
      try {
        const result = await refreshWatchDocument({
          collection,
          documentId: doc.id,
          ref: doc.ref,
          data,
          fetchOfficial,
          onChange,
          now,
          logger,
        });
        if (result.status === "refreshed") {
          summary.refreshed += 1;
          if (result.changed) summary.changed += 1;
        } else {
          summary.skipped += 1;
        }
      } catch (error) {
        summary.failed += 1;
        try {
          await markRefreshFailure(doc.ref, data, nowMs, error);
        } catch (writeError) {
          log(logger, "warn", "Legal watch failure state write failed", {
            collection,
            documentId: doc.id,
            error: String(writeError?.message || writeError || "unknown"),
          });
        }
        log(logger, "warn", "Legal watch refresh failed", {
          collection,
          documentId: doc.id,
          error: String(error?.message || error || "unknown"),
        });
      }
    }
  }

  return summary;
}

module.exports = {
  WATCH_COLLECTIONS,
  DEFAULT_BATCH_SIZE,
  parseWatchRequest,
  refreshWatchDocument,
  refreshDueLegalWatches,
};
