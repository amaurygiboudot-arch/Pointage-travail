"use strict";

const crypto = require("node:crypto");

const CHANGE_COLLECTION = "legal_change_events";
const REANALYSIS_COLLECTION = "legal_reanalysis_jobs";

const CONVENTION_MATTERS = Object.freeze([
  "OVERTIME",
  "NIGHT_WORK",
  "SATURDAY",
  "SUNDAY",
  "PUBLIC_HOLIDAYS",
  "SENIORITY",
  "MINIMUM_PAY",
  "CLASSIFICATION",
  "BONUSES_INDEMNITIES",
  "LEAVE",
  "RTT_REST",
]);

const NATIONAL_MATTERS = Object.freeze([
  "OVERTIME",
  "WORK_TIME",
  "NIGHT_WORK",
  "PUBLIC_HOLIDAYS",
  "MINIMUM_PAY",
  "LEAVE",
  "REST",
  "PAYROLL_LEGAL_BASE",
]);

function matterHintsForSource(sourceFamily) {
  const family = String(sourceFamily || "").toUpperCase();
  if (["ACCO", "KALI", "BOCC"].includes(family)) return [...CONVENTION_MATTERS];
  if (["LEGI", "JORF"].includes(family)) return [...NATIONAL_MATTERS];
  return ["LEGAL_REVIEW"];
}

function deterministicId(parts) {
  return crypto.createHash("sha256").update(parts.join("|")).digest("hex").slice(0, 48);
}

function normalizeChange(change) {
  const sourceFamily = String(change?.sourceFamily || "").trim().toUpperCase();
  const path = String(change?.path || "").trim();
  const sourceCollection = String(change?.collection || "").trim();
  const sourceDocumentId = String(change?.documentId || "").trim();
  const previousPayloadHash = String(change?.previousPayloadHash || "").trim();
  const currentPayloadHash = String(change?.currentPayloadHash || "").trim();
  const scopeType = String(change?.scopeType || "UNKNOWN").trim().toUpperCase();
  const scopeValue = String(change?.scopeValue || "UNKNOWN").trim();

  if (!sourceFamily || !path || !sourceCollection || !sourceDocumentId) return null;
  if (!previousPayloadHash || !currentPayloadHash || previousPayloadHash === currentPayloadHash) return null;

  return {
    sourceFamily,
    path,
    sourceCollection,
    sourceDocumentId,
    previousPayloadHash,
    currentPayloadHash,
    scopeType,
    scopeValue,
  };
}

function buildChangeDocuments(change, detectedAtMs) {
  const normalized = normalizeChange(change);
  if (!normalized) return null;

  const eventId = `evt_${deterministicId([
    normalized.sourceFamily,
    normalized.scopeType,
    normalized.scopeValue,
    normalized.sourceCollection,
    normalized.sourceDocumentId,
    normalized.previousPayloadHash,
    normalized.currentPayloadHash,
  ])}`;
  const jobId = `job_${deterministicId([eventId, "REANALYZE"])}`;
  const matterHints = matterHintsForSource(normalized.sourceFamily);

  return {
    eventId,
    jobId,
    event: {
      schemaVersion: 1,
      status: "DETECTED",
      trigger: "SCHEDULED_WATCH",
      detectedAtMs,
      lastSeenAtMs: detectedAtMs,
      sourceFamily: normalized.sourceFamily,
      path: normalized.path,
      sourceCollection: normalized.sourceCollection,
      sourceDocumentId: normalized.sourceDocumentId,
      scopeType: normalized.scopeType,
      scopeValue: normalized.scopeValue,
      previousPayloadHash: normalized.previousPayloadHash,
      currentPayloadHash: normalized.currentPayloadHash,
      matterHints,
      reanalysisJobId: jobId,
      autoApplyAllowed: false,
    },
    job: {
      schemaVersion: 1,
      status: "PENDING",
      createdAtMs: detectedAtMs,
      updatedAtMs: detectedAtMs,
      sourceFamily: normalized.sourceFamily,
      scopeType: normalized.scopeType,
      scopeValue: normalized.scopeValue,
      triggerEventId: eventId,
      matterHints,
      requiresOfficialRevalidation: true,
      autoApplyAllowed: false,
      attemptCount: 0,
    },
  };
}

function log(logger, level, message, details) {
  const fn = logger?.[level];
  if (typeof fn === "function") fn.call(logger, message, details);
}

async function recordLegalChangeAndQueue({
  db,
  change,
  now = () => Date.now(),
  logger = console,
}) {
  if (!db) throw new TypeError("db is required");
  const detectedAtMs = Number(change?.detectedAtMs ?? now());
  const docs = buildChangeDocuments(change, detectedAtMs);
  if (!docs) return null;

  const eventRef = db.collection(CHANGE_COLLECTION).doc(docs.eventId);
  const jobRef = db.collection(REANALYSIS_COLLECTION).doc(docs.jobId);

  if (typeof db.batch === "function") {
    const batch = db.batch();
    batch.set(eventRef, docs.event, { merge: true });
    batch.set(jobRef, docs.job, { merge: true });
    await batch.commit();
  } else {
    await eventRef.set(docs.event, { merge: true });
    await jobRef.set(docs.job, { merge: true });
  }

  log(logger, "info", "Legal change queued for reanalysis", {
    eventId: docs.eventId,
    jobId: docs.jobId,
    sourceFamily: docs.event.sourceFamily,
    scopeType: docs.event.scopeType,
    scopeValue: docs.event.scopeValue,
  });

  return { eventId: docs.eventId, jobId: docs.jobId };
}

module.exports = {
  CHANGE_COLLECTION,
  REANALYSIS_COLLECTION,
  CONVENTION_MATTERS,
  NATIONAL_MATTERS,
  matterHintsForSource,
  normalizeChange,
  buildChangeDocuments,
  recordLegalChangeAndQueue,
};
