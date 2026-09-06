"use strict";

const crypto = require("node:crypto");

const DAY_MS = 24 * 60 * 60 * 1000;
const LEGAL_CACHE_SCHEMA_VERSION = 1;
const LEGAL_CACHE_PARSER_VERSION = 1;
const KALI_DOCUMENT_TTL_MS = 30 * DAY_MS;
const KALI_SEARCH_TTL_MS = 7 * DAY_MS;

function canonicalJson(value) {
  if (Array.isArray(value)) {
    return `[${value.map((item) => canonicalJson(item)).join(",")}]`;
  }
  if (value !== null && typeof value === "object") {
    const entries = Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`);
    return `{${entries.join(",")}}`;
  }
  return JSON.stringify(value);
}

function requestHash(body) {
  return crypto.createHash("sha256").update(canonicalJson(body ?? {})).digest("hex").slice(0, 40);
}

function cacheSpec(path, body) {
  if (path === "/consult/kaliArticle") {
    const id = String(body?.id ?? "").trim().toUpperCase();
    if (!/^KALIARTI\d+$/.test(id)) return null;
    return {
      collection: "legal_kali_articles",
      documentId: id,
      ttlMs: KALI_DOCUMENT_TTL_MS,
    };
  }

  if (path === "/consult/kaliText") {
    const id = String(body?.id ?? "").trim().toUpperCase();
    if (!/^KALITEXT\d+$/.test(id)) return null;
    return {
      collection: "legal_kali_texts",
      documentId: id,
      ttlMs: KALI_DOCUMENT_TTL_MS,
    };
  }

  if (path === "/consult/kaliContIdcc") {
    const digits = String(body?.id ?? "").replace(/\D/g, "");
    if (!digits || Number(digits) <= 0) return null;
    return {
      collection: "legal_kali_search",
      documentId: `idcc_${Number(digits)}`,
      ttlMs: KALI_SEARCH_TTL_MS,
    };
  }

  if (path === "/list/conventions") {
    return {
      collection: "legal_kali_search",
      documentId: `conventions_${requestHash(body)}`,
      ttlMs: KALI_SEARCH_TTL_MS,
    };
  }

  return null;
}

function cachePayload(data, nowMs) {
  if (!data || data.schemaVersion !== LEGAL_CACHE_SCHEMA_VERSION) return null;
  if (data.parserVersion !== LEGAL_CACHE_PARSER_VERSION) return null;
  if (!Number.isFinite(data.expiresAtMs) || data.expiresAtMs <= nowMs) return null;
  if (typeof data.payloadJson !== "string") return null;

  try {
    return JSON.parse(data.payloadJson);
  } catch (_error) {
    return null;
  }
}

function log(logger, level, message, details) {
  const fn = logger?.[level];
  if (typeof fn === "function") fn.call(logger, message, details);
}

async function resolveWithLegalCache({
  db,
  path,
  body,
  fetchOfficial,
  now = () => Date.now(),
  logger = console,
}) {
  if (typeof fetchOfficial !== "function") {
    throw new TypeError("fetchOfficial must be a function");
  }

  const spec = cacheSpec(path, body);
  if (!spec || !db) return fetchOfficial();

  const nowMs = Number(now());
  let ref = null;
  try {
    ref = db.collection(spec.collection).doc(spec.documentId);
    const snapshot = await ref.get();
    if (snapshot?.exists) {
      const payload = cachePayload(snapshot.data(), nowMs);
      if (payload !== null) {
        log(logger, "info", "Legal KALI cache hit", { path, collection: spec.collection });
        return payload;
      }
    }
  } catch (error) {
    log(logger, "warn", "Legal KALI cache read failed", {
      path,
      collection: spec.collection,
      error: String(error?.message || error || "unknown"),
    });
  }

  const officialPayload = await fetchOfficial();
  const payloadJson = JSON.stringify(officialPayload);
  if (typeof payloadJson !== "string") return officialPayload;

  const cachedAtMs = Number(now());
  try {
    if (!ref) ref = db.collection(spec.collection).doc(spec.documentId);
    await ref.set({
      schemaVersion: LEGAL_CACHE_SCHEMA_VERSION,
      parserVersion: LEGAL_CACHE_PARSER_VERSION,
      source: "legifrance-piste",
      path,
      cachedAtMs,
      expiresAtMs: cachedAtMs + spec.ttlMs,
      payloadJson,
    });
  } catch (error) {
    log(logger, "warn", "Legal KALI cache write failed", {
      path,
      collection: spec.collection,
      error: String(error?.message || error || "unknown"),
    });
  }

  return officialPayload;
}

module.exports = {
  LEGAL_CACHE_SCHEMA_VERSION,
  LEGAL_CACHE_PARSER_VERSION,
  KALI_DOCUMENT_TTL_MS,
  KALI_SEARCH_TTL_MS,
  cacheSpec,
  resolveWithLegalCache,
};
