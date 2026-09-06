"use strict";

const {
  CHANGE_COLLECTION,
  REANALYSIS_COLLECTION,
} = require("./legalChangePipeline");

const TARGET_COLLECTIONS = Object.freeze({
  ACCO: "legal_acco_search",
  KALI: "legal_kali_search",
  LEGI: "legal_legi_search",
});

const DEFAULT_DISPATCH_BATCH_SIZE = 8;
const DEFAULT_TARGET_LIMIT = 30;

function log(logger, level, message, details) {
  const fn = logger?.[level];
  if (typeof fn === "function") fn.call(logger, message, details);
}

function normalizedIdcc(value) {
  const digits = String(value ?? "").replace(/\D/g, "");
  if (!digits || digits.length > 4 || Number(digits) <= 0) return "";
  return String(Number(digits)).padStart(4, "0");
}

function targetScope(job, targetSourceFamily) {
  const family = String(targetSourceFamily || "").toUpperCase();
  const scopeType = String(job?.scopeType || "").toUpperCase();
  const scopeValue = String(job?.scopeValue || "").trim();

  if (family === "LEGI") {
    return { scopeType: "CODE", scopeValue: "CODE_DU_TRAVAIL" };
  }
  if (family === "ACCO" && scopeType === "SIRET" && /^\d{14}$/.test(scopeValue)) {
    return { scopeType: "SIRET", scopeValue };
  }
  if (family === "KALI" && scopeType === "IDCC") {
    const idcc = normalizedIdcc(scopeValue);
    return idcc ? { scopeType: "IDCC", scopeValue: idcc } : null;
  }
  return null;
}

async function requestWatchRefresh(ref, nowMs, jobId, reason) {
  await ref.set({
    expiresAtMs: nowMs,
    nextCheckAtMs: nowMs,
    revalidationRequestedAtMs: nowMs,
    reanalysisJobId: jobId,
    revalidationReason: reason,
  }, { merge: true });
}

async function queueTargetWatches({
  db,
  targetSourceFamily,
  scope,
  jobId,
  reason,
  nowMs,
  limit = DEFAULT_TARGET_LIMIT,
}) {
  const family = String(targetSourceFamily || "").toUpperCase();
  const collectionName = TARGET_COLLECTIONS[family];
  if (!collectionName || !scope) return 0;

  const boundedLimit = Math.max(1, Math.min(100, Number.parseInt(limit, 10) || DEFAULT_TARGET_LIMIT));
  const collection = db.collection(collectionName);
  const touched = new Set();

  if (family === "KALI" && scope.scopeType === "IDCC") {
    const directId = `idcc_${Number(scope.scopeValue)}`;
    const directRef = collection.doc(directId);
    const snapshot = typeof directRef.get === "function" ? await directRef.get() : null;
    if (snapshot?.exists) {
      await requestWatchRefresh(directRef, nowMs, jobId, reason);
      touched.add(directId);
    }
  }

  if (typeof collection.where !== "function") return touched.size;
  const snapshot = await collection.where("scopeValue", "==", scope.scopeValue).limit(boundedLimit).get();
  const docs = Array.isArray(snapshot?.docs) ? snapshot.docs : [];
  for (const doc of docs) {
    if (touched.has(doc.id)) continue;
    const data = typeof doc.data === "function" ? doc.data() : null;
    if (String(data?.scopeType || "").toUpperCase() !== scope.scopeType) continue;
    if (data?.watchEnabled !== true) continue;
    await requestWatchRefresh(doc.ref, nowMs, jobId, reason);
    touched.add(doc.id);
  }

  return touched.size;
}

function jobTargets(job) {
  const values = Array.isArray(job?.targetSourceFamilies) ? job.targetSourceFamilies : [];
  return [...new Set(values.map((value) => String(value || "").toUpperCase()).filter(Boolean))];
}

async function dispatchJob({
  db,
  jobId,
  job,
  nowMs,
  logger = console,
  targetLimit = DEFAULT_TARGET_LIMIT,
}) {
  const targets = jobTargets(job);
  if (!targets.length) {
    await db.collection(REANALYSIS_COLLECTION).doc(jobId).set({
      status: "WAITING_CONFIGURATION",
      updatedAtMs: nowMs,
      dispatchedAtMs: nowMs,
      dispatchAttemptCount: (Number(job?.dispatchAttemptCount) || 0) + 1,
    }, { merge: true });
    return { status: "WAITING_CONFIGURATION", queuedWatches: 0, unresolvedTargets: [], readySources: [] };
  }

  let queuedWatches = 0;
  const unresolvedTargets = [];
  const readySources = [];
  const perTarget = {};
  const sourceFamily = String(job?.sourceFamily || "").toUpperCase();
  const targetDetails = Array.isArray(job?.revalidationTargets) ? job.revalidationTargets : [];

  for (const family of targets) {
    const target = targetDetails.find((candidate) => String(candidate?.sourceFamily || "").toUpperCase() === family);
    const reason = String(target?.reason || "LEGAL_CHANGE_REVALIDATION");

    if (family === sourceFamily && reason === "DIRECT_SOURCE_CHANGE") {
      readySources.push(family);
      perTarget[family] = 0;
      continue;
    }

    const scope = targetScope(job, family);
    if (!scope) {
      unresolvedTargets.push(family);
      perTarget[family] = 0;
      continue;
    }
    const count = await queueTargetWatches({
      db,
      targetSourceFamily: family,
      scope,
      jobId,
      reason,
      nowMs,
      limit: targetLimit,
    });
    perTarget[family] = count;
    queuedWatches += count;
  }

  const status = unresolvedTargets.length
    ? "WAITING_SCOPE"
    : queuedWatches > 0
      ? "REVALIDATION_QUEUED"
      : readySources.length > 0
        ? "READY_FOR_ANALYSIS"
        : "WAITING_WATCH";
  const patch = {
    status,
    updatedAtMs: nowMs,
    dispatchedAtMs: nowMs,
    dispatchAttemptCount: (Number(job?.dispatchAttemptCount) || 0) + 1,
    queuedWatchCount: queuedWatches,
    queuedWatchCountBySource: perTarget,
    unresolvedTargetSourceFamilies: unresolvedTargets,
    readySourceFamilies: readySources,
    autoApplyAllowed: false,
  };
  await db.collection(REANALYSIS_COLLECTION).doc(jobId).set(patch, { merge: true });

  const eventId = String(job?.latestTriggerEventId || job?.triggerEventId || "").trim();
  if (eventId) {
    await db.collection(CHANGE_COLLECTION).doc(eventId).set({
      status,
      updatedAtMs: nowMs,
      reanalysisJobId: jobId,
      queuedWatchCount: queuedWatches,
      unresolvedTargetSourceFamilies: unresolvedTargets,
      readySourceFamilies: readySources,
    }, { merge: true });
  }

  log(logger, "info", "Legal reanalysis job dispatched", {
    jobId,
    status,
    queuedWatches,
    unresolvedTargets,
    readySources,
  });
  return { status, queuedWatches, unresolvedTargets, readySources };
}

async function dispatchPendingLegalReanalysis({
  db,
  now = () => Date.now(),
  logger = console,
  batchSize = DEFAULT_DISPATCH_BATCH_SIZE,
  targetLimit = DEFAULT_TARGET_LIMIT,
}) {
  if (!db) {
    return { scanned: 0, dispatched: 0, queuedWatches: 0, waitingScope: 0, waitingWatch: 0, readyForAnalysis: 0, failed: 0 };
  }

  const boundedBatch = Math.max(1, Math.min(50, Number.parseInt(batchSize, 10) || DEFAULT_DISPATCH_BATCH_SIZE));
  const summary = { scanned: 0, dispatched: 0, queuedWatches: 0, waitingScope: 0, waitingWatch: 0, readyForAnalysis: 0, failed: 0 };
  let snapshot;
  try {
    snapshot = await db.collection(REANALYSIS_COLLECTION).where("status", "==", "PENDING").limit(boundedBatch).get();
  } catch (error) {
    log(logger, "warn", "Legal reanalysis scan failed", {
      error: String(error?.message || error || "unknown"),
    });
    summary.failed += 1;
    return summary;
  }

  const docs = Array.isArray(snapshot?.docs) ? snapshot.docs : [];
  for (const doc of docs) {
    summary.scanned += 1;
    const job = typeof doc.data === "function" ? doc.data() : null;
    try {
      const result = await dispatchJob({
        db,
        jobId: doc.id,
        job,
        nowMs: Number(now()),
        logger,
        targetLimit,
      });
      summary.dispatched += 1;
      summary.queuedWatches += result.queuedWatches;
      if (result.status === "WAITING_SCOPE") summary.waitingScope += 1;
      if (result.status === "WAITING_WATCH") summary.waitingWatch += 1;
      if (result.status === "READY_FOR_ANALYSIS") summary.readyForAnalysis += 1;
    } catch (error) {
      summary.failed += 1;
      try {
        await doc.ref.set({
          lastDispatchErrorAtMs: Number(now()),
          lastDispatchError: String(error?.message || error || "unknown").slice(0, 240),
          dispatchAttemptCount: (Number(job?.dispatchAttemptCount) || 0) + 1,
        }, { merge: true });
      } catch (_writeError) {
        // The next scheduler pass can retry the still-PENDING job.
      }
      log(logger, "warn", "Legal reanalysis dispatch failed", {
        jobId: doc.id,
        error: String(error?.message || error || "unknown"),
      });
    }
  }

  return summary;
}

module.exports = {
  TARGET_COLLECTIONS,
  DEFAULT_DISPATCH_BATCH_SIZE,
  DEFAULT_TARGET_LIMIT,
  targetScope,
  requestWatchRefresh,
  queueTargetWatches,
  jobTargets,
  dispatchJob,
  dispatchPendingLegalReanalysis,
};