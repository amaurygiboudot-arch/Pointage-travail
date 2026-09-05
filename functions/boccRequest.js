"use strict";

function normalizedIdcc(value) {
  const digits = String(value ?? "").replace(/\D/g, "");
  if (!digits || digits.length > 4 || Number(digits) <= 0) return "";
  return String(Number(digits));
}

function firstIdcc(body) {
  if (Array.isArray(body?.idccs)) {
    for (const raw of body.idccs) {
      const idcc = normalizedIdcc(raw);
      if (idcc) return idcc;
    }
  }
  return normalizedIdcc(body?.idcc);
}

function normalizedInterval(value) {
  const interval = typeof value === "string" ? value.trim().replace(/\s+/g, " ") : "";
  return /^\d{2}\/\d{2}\/\d{4} > \d{2}\/\d{2}\/\d{4}$/.test(interval) ? interval : "";
}

function boundedInt(value, min, max, fallback) {
  const parsed = Number.parseInt(value, 10);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.min(max, Math.max(min, parsed));
}

/**
 * /list/boccsAndTexts utilise BoccAndTextListRequest : un IDCC scalaire,
 * une période de publication et la pagination.
 */
function normalizeBoccRequest(body) {
  const safeBody = body && typeof body === "object" && !Array.isArray(body) ? body : {};
  const idcc = firstIdcc(safeBody);
  const intervalPublication = normalizedInterval(safeBody.intervalPublication);
  if (!idcc || !intervalPublication) return {};
  return {
    idcc,
    intervalPublication,
    pageNumber: boundedInt(safeBody.pageNumber, 1, 100, 1),
    pageSize: boundedInt(safeBody.pageSize, 1, 100, 50),
    sortValue: safeBody.sortValue === "BOCC_SORT_ASC" ? "BOCC_SORT_ASC" : "BOCC_SORT_DESC",
  };
}

module.exports = { normalizeBoccRequest };
