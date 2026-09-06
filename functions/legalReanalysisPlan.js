"use strict";

const { REANALYSIS_COLLECTION } = require("./legalChangePipeline");

const READY_STATUS = "READY_FOR_ANALYSIS";
const MAX_JOBS = 20;
const PER_SCOPE_LIMIT = 25;

function normalizeIdcc(value) {
  const digits = String(value ?? "").replace(/\D/g, "");
  if (!digits || digits.length > 4 || Number(digits) <= 0) return "";
  return String(Number(digits)).padStart(4, "0");
}

function normalizeSiret(value) {
  const digits = String(value ?? "").replace(/\D/g, "");
  return digits.length === 14 ? digits : "";
}

function normalizePlanRequest(data) {
  return {
    idcc: normalizeIdcc(data?.idcc),
    siret: normalizeSiret(data?.siret),
  };
}

function stringList(value) {
  return Array.isArray(value)
    ? [...new Set(value.map((item) => String(item || "").trim().toUpperCase()).filter(Boolean))]
    : [];
}

function analysisKindsForJob(job) {
  const sourceFamily = String(job?.sourceFamily || "").trim().toUpperCase();
  const matters = stringList(job?.matterHints);
  const targets = stringList(job?.targetSourceFamilies);
  const kinds = [];

  if (["KALI", "BOCC"].includes(sourceFamily) && targets.includes("KALI") && matters.includes("OVERTIME")) {
    kinds.push("KALI_OVERTIME");
  }
  if (["LEGI", "JORF"].includes(sourceFamily) && (sourceFamily === "LEGI" || targets.includes("LEGI"))) {
    kinds.push("LEGI_ALL");
  }
  if (sourceFamily === "ACCO") {
    kinds.push("ACCO_PENDING_PARSER");
  }
  return kinds;
}

function safeReadyJob(id, data) {
  if (!data || String(data.status || "") !== READY_STATUS) return null;
  const sourceFamily = String(data.sourceFamily || "").trim().toUpperCase();
  if (!sourceFamily) return null;

  const revisionAtMs = [data.lastQueuedAtMs, data.revalidationCompletedAtMs, data.updatedAtMs, data.createdAtMs]
    .map(Number)
    .find((value) => Number.isFinite(value) && value > 0) || 0;

  return {
    jobId: String(id || ""),
    revisionKey: `${String(id || "")}:${Math.trunc(revisionAtMs)}`,
    status: READY_STATUS,
    sourceFamily,
    sourceRole: String(data.sourceRole || ""),
    scopeType: String(data.scopeType || "").toUpperCase(),
    scopeValue: String(data.scopeValue || ""),
    matterHints: stringList(data.matterHints),
    targetSourceFamilies: stringList(data.targetSourceFamilies),
    completedSourceFamilies: stringList(data.completedSourceFamilies),
    analysisKinds: analysisKindsForJob(data),
    createdAtMs: Number(data.createdAtMs) || 0,
    lastQueuedAtMs: Number(data.lastQueuedAtMs) || 0,
    revalidationCompletedAtMs: Number(data.revalidationCompletedAtMs) || 0,
    autoApplyAllowed: false,
  };
}

async function queryScope(db, scopeValue, limit = PER_SCOPE_LIMIT) {
  if (!scopeValue) return [];
  const bounded = Math.max(1, Math.min(50, Number.parseInt(limit, 10) || PER_SCOPE_LIMIT));
  const snapshot = await db.collection(REANALYSIS_COLLECTION)
    .where("scopeValue", "==", scopeValue)
    .limit(bounded)
    .get();
  return Array.isArray(snapshot?.docs) ? snapshot.docs : [];
}

async function listReadyLegalReanalysis({ db, idcc, siret, limit = MAX_JOBS }) {
  if (!db) return [];
  const normalizedIdcc = normalizeIdcc(idcc);
  const normalizedSiret = normalizeSiret(siret);
  const scopes = [normalizedIdcc, normalizedSiret, "CODE_DU_TRAVAIL", "FRANCE"].filter(Boolean);
  const uniqueScopes = [...new Set(scopes)];
  const byId = new Map();

  for (const scopeValue of uniqueScopes) {
    const docs = await queryScope(db, scopeValue);
    for (const doc of docs) {
      if (byId.has(doc.id)) continue;
      const data = typeof doc.data === "function" ? doc.data() : null;
      const safe = safeReadyJob(doc.id, data);
      if (!safe) continue;

      const scopeType = safe.scopeType;
      const belongsToRequest =
        (scopeType === "IDCC" && normalizedIdcc && safe.scopeValue === normalizedIdcc) ||
        (scopeType === "SIRET" && normalizedSiret && safe.scopeValue === normalizedSiret) ||
        (scopeType === "CODE" && safe.scopeValue === "CODE_DU_TRAVAIL") ||
        (scopeType === "GLOBAL" && safe.scopeValue === "FRANCE");
      if (belongsToRequest) byId.set(doc.id, safe);
    }
  }

  const bounded = Math.max(1, Math.min(MAX_JOBS, Number.parseInt(limit, 10) || MAX_JOBS));
  return [...byId.values()]
    .sort((a, b) => (b.lastQueuedAtMs || b.revalidationCompletedAtMs || b.createdAtMs) - (a.lastQueuedAtMs || a.revalidationCompletedAtMs || a.createdAtMs))
    .slice(0, bounded);
}

module.exports = {
  READY_STATUS,
  MAX_JOBS,
  normalizeIdcc,
  normalizeSiret,
  normalizePlanRequest,
  analysisKindsForJob,
  safeReadyJob,
  listReadyLegalReanalysis,
};