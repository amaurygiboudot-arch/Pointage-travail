"use strict";

const crypto = require("node:crypto");
const { watchSpec } = require("./legalKaliCache");

const SCOPE_WATCH_COLLECTION = "legal_watch_scopes";
const SCOPE_WATCH_SCHEMA_VERSION = 1;
const BOCC_WINDOW_DAYS = 180;
const BOCC_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000;
const RETRY_INTERVAL_MS = 6 * 60 * 60 * 1000;
const DEFAULT_SCOPE_BATCH_SIZE = 6;
const MAX_SEEN_REFERENCE_KEYS = 300;

function normalizedIdcc(value) {
  const digits = String(value ?? "").replace(/\D/g, "");
  if (!digits || digits.length > 4 || Number(digits) <= 0) return "";
  return String(Number(digits)).padStart(4, "0");
}

function formatParisDate(ms) {
  const parts = new Intl.DateTimeFormat("fr-FR", {
    timeZone: "Europe/Paris",
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
  }).formatToParts(new Date(ms));
  const value = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return `${value.day}/${value.month}/${value.year}`;
}

function rollingBoccBody(idcc, nowMs, windowDays = BOCC_WINDOW_DAYS) {
  const normalized = normalizedIdcc(idcc);
  if (!normalized) return null;
  const days = Math.max(30, Math.min(730, Number.parseInt(windowDays, 10) || BOCC_WINDOW_DAYS));
  const fromMs = nowMs - days * 24 * 60 * 60 * 1000;
  return {
    idcc: String(Number(normalized)),
    intervalPublication: `${formatParisDate(fromMs)} > ${formatParisDate(nowMs)}`,
    pageNumber: 1,
    pageSize: 100,
    sortValue: "BOCC_SORT_DESC",
  };
}

function value(map, ...keys) {
  if (!map || typeof map !== "object" || Array.isArray(map)) return "";
  for (const wanted of keys) {
    const key = Object.keys(map).find((candidate) => candidate.toLowerCase() === wanted.toLowerCase());
    if (key && map[key] != null) return String(map[key]).trim();
  }
  return "";
}

function boccReferenceKey(text) {
  if (!text || typeof text !== "object" || Array.isArray(text)) return "";
  const fileName = value(text, "fileName", "filename", "id");
  if (!fileName || !fileName.toLowerCase().endsWith(".pdf")) return "";
  if (fileName.length > 160 || !/^[a-zA-Z0-9_.-]+$/.test(fileName)) return "";
  return fileName.toLowerCase();
}

function extractBoccReferenceKeys(payload) {
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) return [];
  const keys = [];
  const directTexts = Array.isArray(payload.texts) ? payload.texts : null;
  if (directTexts) {
    for (const text of directTexts) {
      const key = boccReferenceKey(text);
      if (key) keys.push(key);
    }
  }

  const results = Array.isArray(payload.results) ? payload.results : [];
  for (const result of results) {
    if (!result || typeof result !== "object" || Array.isArray(result)) continue;
    const texts = Array.isArray(result.texts) ? result.texts : [];
    for (const text of texts) {
      const key = boccReferenceKey(text);
      if (key) keys.push(key);
    }
  }
  return [...new Set(keys)].sort();
}

function referenceHash(keys) {
  return crypto.createHash("sha256").update(JSON.stringify([...keys].sort())).digest("hex");
}

function scopeWatchId(sourceFamily, scopeType, scopeValue) {
  return `${String(sourceFamily).toLowerCase()}_${String(scopeType).toLowerCase()}_${String(scopeValue).toLowerCase()}`
    .replace(/[^a-z0-9_-]/g, "_")
    .slice(0, 180);
}

function boccScopeWatchData(idcc, nowMs) {
  const normalized = normalizedIdcc(idcc);
  if (!normalized) return null;
  return {
    schemaVersion: SCOPE_WATCH_SCHEMA_VERSION,
    sourceFamily: "BOCC",
    sourceRole: "CHANGE_SIGNAL",
    watchType: "BOCC_ROLLING_IDCC",
    scopeType: "IDCC",
    scopeValue: normalized,
    windowDays: BOCC_WINDOW_DAYS,
    nextCheckAtMs: nowMs,
    autoApplyAllowed: false,
  };
}

function log(logger, level, message, details) {
  const fn = logger?.[level];
  if (typeof fn === "function") fn.call(logger, message, details);
}

async function ensureBoccScopeWatchForIdcc({ db, idcc, now = () => Date.now(), logger = console }) {
  if (!db) return false;
  const nowMs = Number(now());
  const data = boccScopeWatchData(idcc, nowMs);
  if (!data) return false;
  const id = scopeWatchId("BOCC", "IDCC", data.scopeValue);
  try {
    const ref = db.collection(SCOPE_WATCH_COLLECTION).doc(id);
    const snapshot = await ref.get();
    if (snapshot?.exists) {
      const existing = snapshot.data() || {};
      const patch = {};
      if (existing.schemaVersion !== SCOPE_WATCH_SCHEMA_VERSION) patch.schemaVersion = SCOPE_WATCH_SCHEMA_VERSION;
      if (existing.watchType !== data.watchType) patch.watchType = data.watchType;
      if (existing.scopeType !== "IDCC") patch.scopeType = "IDCC";
      if (existing.scopeValue !== data.scopeValue) patch.scopeValue = data.scopeValue;
      if (existing.sourceFamily !== "BOCC") patch.sourceFamily = "BOCC";
      if (existing.sourceRole !== "CHANGE_SIGNAL") patch.sourceRole = "CHANGE_SIGNAL";
      if (existing.autoApplyAllowed !== false) patch.autoApplyAllowed = false;
      if (!Number.isFinite(existing.nextCheckAtMs)) patch.nextCheckAtMs = nowMs;
      if (!Number.isFinite(existing.windowDays)) patch.windowDays = BOCC_WINDOW_DAYS;
      if (Object.keys(patch).length) await ref.set(patch, { merge: true });
      return Object.keys(patch).length > 0;
    }
    await ref.set(data, { merge: true });
    log(logger, "info", "BOCC rolling scope watch created", { idcc: data.scopeValue });
    return true;
  } catch (error) {
    log(logger, "warn", "BOCC rolling scope watch registration failed", {
      idcc: data.scopeValue,
      error: String(error?.message || error || "unknown"),
    });
    return false;
  }
}

async function ensureDerivedLegalScopeWatches({ db, path, body, now = () => Date.now(), logger = console }) {
  const watch = watchSpec(path, body);
  if (!watch || watch.scopeType !== "IDCC") return false;
  if (!["KALI", "BOCC"].includes(watch.sourceFamily)) return false;
  return ensureBoccScopeWatchForIdcc({ db, idcc: watch.scopeValue, now, logger });
}

function parseScopeWatch(data) {
  if (!data || data.schemaVersion !== SCOPE_WATCH_SCHEMA_VERSION) return null;
  if (data.watchType !== "BOCC_ROLLING_IDCC") return null;
  const idcc = normalizedIdcc(data.scopeValue);
  if (!idcc || data.scopeType !== "IDCC" || data.sourceFamily !== "BOCC") return null;
  return { idcc, windowDays: Number(data.windowDays) || BOCC_WINDOW_DAYS };
}

function mergeSeenReferenceKeys(previousKeys, currentKeys) {
  const merged = [...new Set([...previousKeys, ...currentKeys])];
  return merged.slice(Math.max(0, merged.length - MAX_SEEN_REFERENCE_KEYS)).sort();
}

async function refreshScopeWatchDocument({
  documentId,
  ref,
  data,
  fetchOfficial,
  onChange = null,
  now = () => Date.now(),
  logger = console,
}) {
  const watch = parseScopeWatch(data);
  if (!watch) return { status: "skipped", changed: false };
  const checkedAtMs = Number(now());
  const body = rollingBoccBody(watch.idcc, checkedAtMs, watch.windowDays);
  const payload = await fetchOfficial("/list/boccsAndTexts", body);
  const currentKeys = extractBoccReferenceKeys(payload);
  const previousKeys = Array.isArray(data.referenceKeys)
    ? [...new Set(data.referenceKeys.filter((item) => typeof item === "string"))].sort()
    : [];
  const previousSet = new Set(previousKeys);
  const addedKeys = previousKeys.length ? currentKeys.filter((key) => !previousSet.has(key)) : [];
  const seenKeys = mergeSeenReferenceKeys(previousKeys, currentKeys);
  const previousHash = typeof data.referenceHash === "string" && data.referenceHash
    ? data.referenceHash
    : previousKeys.length
      ? referenceHash(previousKeys)
      : "";
  const currentHash = referenceHash(seenKeys);
  const changed = addedKeys.length > 0 && Boolean(previousHash);

  if (changed && typeof onChange === "function") {
    await onChange({
      sourceFamily: "BOCC",
      path: "/list/boccsAndTexts",
      collection: SCOPE_WATCH_COLLECTION,
      documentId,
      scopeType: "IDCC",
      scopeValue: watch.idcc,
      previousPayloadHash: previousHash,
      currentPayloadHash: currentHash,
      detectedAtMs: checkedAtMs,
      addedReferenceCount: addedKeys.length,
    });
  }

  await ref.set({
    referenceKeys: seenKeys,
    referenceHash: currentHash,
    lastWindowReferenceCount: currentKeys.length,
    lastAddedReferenceCount: addedKeys.length,
    lastOfficialCheckAtMs: checkedAtMs,
    lastRequestJson: JSON.stringify(body),
    nextCheckAtMs: checkedAtMs + BOCC_CHECK_INTERVAL_MS,
    lastRefreshErrorAtMs: null,
    lastRefreshError: null,
    refreshFailureCount: 0,
  }, { merge: true });

  log(logger, "info", "BOCC rolling scope watch refreshed", {
    idcc: watch.idcc,
    documentId,
    windowReferences: currentKeys.length,
    addedReferences: addedKeys.length,
  });
  return { status: "refreshed", changed, added: addedKeys.length };
}

async function refreshDueLegalScopeWatches({
  db,
  fetchOfficial,
  onChange = null,
  now = () => Date.now(),
  logger = console,
  batchSize = DEFAULT_SCOPE_BATCH_SIZE,
}) {
  const summary = { scanned: 0, refreshed: 0, changed: 0, failed: 0, skipped: 0 };
  if (!db || typeof fetchOfficial !== "function") return summary;
  const nowMs = Number(now());
  const limit = Math.max(1, Math.min(25, Number.parseInt(batchSize, 10) || DEFAULT_SCOPE_BATCH_SIZE));
  let snapshot;
  try {
    snapshot = await db.collection(SCOPE_WATCH_COLLECTION)
      .where("nextCheckAtMs", "<=", nowMs)
      .orderBy("nextCheckAtMs", "asc")
      .limit(limit)
      .get();
  } catch (error) {
    summary.failed += 1;
    log(logger, "warn", "Legal scope watch scan failed", { error: String(error?.message || error || "unknown") });
    return summary;
  }

  const docs = Array.isArray(snapshot?.docs) ? snapshot.docs : [];
  for (const doc of docs) {
    summary.scanned += 1;
    const data = typeof doc.data === "function" ? doc.data() : null;
    try {
      const result = await refreshScopeWatchDocument({
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
      const previous = Number(data?.refreshFailureCount);
      try {
        await doc.ref.set({
          refreshFailureCount: Number.isFinite(previous) && previous >= 0 ? previous + 1 : 1,
          lastRefreshErrorAtMs: nowMs,
          lastRefreshError: String(error?.message || error || "unknown").slice(0, 240),
          nextCheckAtMs: nowMs + RETRY_INTERVAL_MS,
        }, { merge: true });
      } catch (_writeError) {}
      log(logger, "warn", "Legal scope watch refresh failed", {
        documentId: doc.id,
        error: String(error?.message || error || "unknown"),
      });
    }
  }
  return summary;
}

module.exports = {
  SCOPE_WATCH_COLLECTION,
  SCOPE_WATCH_SCHEMA_VERSION,
  BOCC_WINDOW_DAYS,
  BOCC_CHECK_INTERVAL_MS,
  MAX_SEEN_REFERENCE_KEYS,
  normalizedIdcc,
  formatParisDate,
  rollingBoccBody,
  boccReferenceKey,
  extractBoccReferenceKeys,
  referenceHash,
  scopeWatchId,
  boccScopeWatchData,
  ensureBoccScopeWatchForIdcc,
  ensureDerivedLegalScopeWatches,
  parseScopeWatch,
  mergeSeenReferenceKeys,
  refreshScopeWatchDocument,
  refreshDueLegalScopeWatches,
};
