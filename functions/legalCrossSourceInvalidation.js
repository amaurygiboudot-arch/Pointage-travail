"use strict";

const KALI_SEARCH_COLLECTION = "legal_kali_search";
const DEFAULT_INVALIDATION_LIMIT = 25;

function normalizedIdcc(value) {
  const digits = String(value ?? "").replace(/\D/g, "");
  if (!digits || digits.length > 4 || Number(digits) <= 0) return "";
  return String(Number(digits)).padStart(4, "0");
}

function log(logger, level, message, details) {
  const fn = logger?.[level];
  if (typeof fn === "function") fn.call(logger, message, details);
}

async function invalidateRef(ref, nowMs, reason) {
  await ref.set({
    expiresAtMs: nowMs,
    nextCheckAtMs: nowMs,
    invalidatedAtMs: nowMs,
    invalidationReason: reason,
  }, { merge: true });
}

async function invalidateKaliForIdcc({
  db,
  idcc,
  now = () => Date.now(),
  logger = console,
  limit = DEFAULT_INVALIDATION_LIMIT,
}) {
  if (!db) return 0;
  const normalized = normalizedIdcc(idcc);
  if (!normalized) return 0;

  const nowMs = Number(now());
  const collection = db.collection(KALI_SEARCH_COLLECTION);
  const touched = new Set();
  const directId = `idcc_${Number(normalized)}`;

  try {
    const directRef = collection.doc(directId);
    const directSnapshot = typeof directRef.get === "function" ? await directRef.get() : null;
    if (directSnapshot?.exists) {
      await invalidateRef(directRef, nowMs, "BOCC_CHANGE");
      touched.add(directId);
    }

    if (typeof collection.where === "function") {
      const boundedLimit = Math.max(1, Math.min(100, Number.parseInt(limit, 10) || DEFAULT_INVALIDATION_LIMIT));
      const snapshot = await collection.where("scopeValue", "==", normalized).limit(boundedLimit).get();
      const docs = Array.isArray(snapshot?.docs) ? snapshot.docs : [];
      for (const doc of docs) {
        const data = typeof doc.data === "function" ? doc.data() : null;
        if (String(data?.scopeType || "").toUpperCase() !== "IDCC") continue;
        if (touched.has(doc.id)) continue;
        await invalidateRef(doc.ref, nowMs, "BOCC_CHANGE");
        touched.add(doc.id);
      }
    }

    if (touched.size > 0) {
      log(logger, "info", "BOCC change invalidated KALI caches", {
        idcc: normalized,
        invalidated: touched.size,
      });
    }
    return touched.size;
  } catch (error) {
    log(logger, "warn", "Dependent KALI cache invalidation failed", {
      idcc: normalized,
      error: String(error?.message || error || "unknown"),
    });
    return touched.size;
  }
}

async function invalidateDependentLegalCaches({
  db,
  change,
  now = () => Date.now(),
  logger = console,
}) {
  const sourceFamily = String(change?.sourceFamily || "").toUpperCase();
  const scopeType = String(change?.scopeType || "").toUpperCase();
  if (sourceFamily !== "BOCC" || scopeType !== "IDCC") return 0;

  return invalidateKaliForIdcc({
    db,
    idcc: change?.scopeValue,
    now,
    logger,
  });
}

module.exports = {
  KALI_SEARCH_COLLECTION,
  DEFAULT_INVALIDATION_LIMIT,
  normalizedIdcc,
  invalidateKaliForIdcc,
  invalidateDependentLegalCaches,
};
