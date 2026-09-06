"use strict";

const crypto = require("node:crypto");

const HOUR_MS = 60 * 60 * 1000;
const DAY_MS = 24 * HOUR_MS;
const LEGAL_CACHE_SCHEMA_VERSION = 1;
const LEGAL_CACHE_PARSER_VERSION = 1;

const KALI_DOCUMENT_TTL_MS = 30 * DAY_MS;
const KALI_SEARCH_TTL_MS = 7 * DAY_MS;
const KALI_RULE_SEARCH_TTL_MS = 1 * DAY_MS;
const ACCO_DOCUMENT_TTL_MS = 30 * DAY_MS;
const ACCO_SEARCH_TTL_MS = 1 * DAY_MS;
const LEGI_DOCUMENT_TTL_MS = 30 * DAY_MS;
const LEGI_SEARCH_TTL_MS = 1 * DAY_MS;
const BOCC_DOCUMENT_TTL_MS = 30 * DAY_MS;
const BOCC_SEARCH_TTL_MS = 1 * DAY_MS;
const JORF_DOCUMENT_TTL_MS = 30 * DAY_MS;
const JORF_FEED_TTL_MS = 6 * HOUR_MS;

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

function payloadHash(payloadJson) {
  return crypto.createHash("sha256").update(String(payloadJson ?? "")).digest("hex");
}

function officialId(value, prefix) {
  const id = String(value ?? "").trim().toUpperCase();
  return new RegExp(`^${prefix}\\d+$`).test(id) ? id : "";
}

function normalizedIdcc(value) {
  const digits = String(value ?? "").replace(/\D/g, "");
  if (!digits || digits.length > 4 || Number(digits) <= 0) return "";
  return String(Number(digits)).padStart(4, "0");
}

function findSearchCriterion(body, typeChamp) {
  const champs = Array.isArray(body?.recherche?.champs) ? body.recherche.champs : [];
  for (const champ of champs) {
    if (String(champ?.typeChamp ?? "").toUpperCase() !== typeChamp) continue;
    const criteres = Array.isArray(champ?.criteres) ? champ.criteres : [];
    for (const critere of criteres) {
      const value = String(critere?.valeur ?? "").trim();
      if (value) return value;
    }
  }
  return "";
}

function findSiret(body) {
  const direct = findSearchCriterion(body, "SIRET_RAISON_SOCIALE").replace(/\D/g, "");
  if (direct.length === 14) return direct;

  const filtres = Array.isArray(body?.recherche?.filtres) ? body.recherche.filtres : [];
  for (const filtre of filtres) {
    if (String(filtre?.facette ?? "").toUpperCase() !== "SIRET_RAISON_SOCIALE") continue;
    const values = Array.isArray(filtre?.valeurs) ? filtre.valeurs : [];
    for (const raw of values) {
      const digits = String(raw ?? "").replace(/\D/g, "");
      if (digits.length === 14) return digits;
    }
  }

  const allChamps = Array.isArray(body?.recherche?.champs) ? body.recherche.champs : [];
  for (const champ of allChamps) {
    const criteres = Array.isArray(champ?.criteres) ? champ.criteres : [];
    for (const critere of criteres) {
      const digits = String(critere?.valeur ?? "").replace(/\D/g, "");
      if (digits.length === 14) return digits;
    }
  }
  return "";
}

function requestScope(path, body) {
  if (path === "/consult/kaliContIdcc") {
    const idcc = normalizedIdcc(body?.id);
    return idcc ? { scopeType: "IDCC", scopeValue: idcc } : null;
  }

  if (path === "/list/boccsAndTexts") {
    const idcc = normalizedIdcc(body?.idcc);
    return idcc ? { scopeType: "IDCC", scopeValue: idcc } : null;
  }

  if (path === "/search") {
    const fond = String(body?.fond ?? "").trim().toUpperCase();
    if (fond === "KALI") {
      const idcc = normalizedIdcc(findSearchCriterion(body, "IDCC"));
      return idcc ? { scopeType: "IDCC", scopeValue: idcc } : null;
    }
    if (fond === "ACCO") {
      const siret = findSiret(body);
      return siret ? { scopeType: "SIRET", scopeValue: siret } : null;
    }
    if (fond === "CODE_DATE") {
      return { scopeType: "CODE", scopeValue: "CODE_DU_TRAVAIL" };
    }
  }

  if (path === "/consult/lastNJo" || path === "/list/conventions") {
    return { scopeType: "GLOBAL", scopeValue: "FRANCE" };
  }

  return null;
}

function cacheSpec(path, body) {
  if (path === "/search") {
    const fond = String(body?.fond ?? "").trim().toUpperCase();
    const search = `search_${requestHash(body)}`;
    if (fond === "KALI") {
      return { sourceFamily: "KALI", collection: "legal_kali_search", documentId: search, ttlMs: KALI_RULE_SEARCH_TTL_MS };
    }
    if (fond === "ACCO") {
      return { sourceFamily: "ACCO", collection: "legal_acco_search", documentId: search, ttlMs: ACCO_SEARCH_TTL_MS };
    }
    if (fond === "CODE_DATE") {
      return { sourceFamily: "LEGI", collection: "legal_legi_search", documentId: search, ttlMs: LEGI_SEARCH_TTL_MS };
    }
    return null;
  }

  if (path === "/consult/acco") {
    const id = officialId(body?.id, "ACCOTEXT");
    return id ? { sourceFamily: "ACCO", collection: "legal_acco_texts", documentId: id, ttlMs: ACCO_DOCUMENT_TTL_MS } : null;
  }

  if (path === "/consult/kaliArticle") {
    const id = officialId(body?.id, "KALIARTI");
    return id ? { sourceFamily: "KALI", collection: "legal_kali_articles", documentId: id, ttlMs: KALI_DOCUMENT_TTL_MS } : null;
  }

  if (path === "/consult/kaliText") {
    const id = officialId(body?.id, "KALITEXT");
    return id ? { sourceFamily: "KALI", collection: "legal_kali_texts", documentId: id, ttlMs: KALI_DOCUMENT_TTL_MS } : null;
  }

  if (path === "/consult/kaliContIdcc") {
    const digits = String(body?.id ?? "").replace(/\D/g, "");
    if (!digits || Number(digits) <= 0) return null;
    return { sourceFamily: "KALI", collection: "legal_kali_search", documentId: `idcc_${Number(digits)}`, ttlMs: KALI_SEARCH_TTL_MS };
  }

  if (path === "/list/conventions") {
    return { sourceFamily: "KALI", collection: "legal_kali_search", documentId: `conventions_${requestHash(body)}`, ttlMs: KALI_SEARCH_TTL_MS };
  }

  if (path === "/consult/getArticle") {
    const id = officialId(body?.id, "LEGIARTI");
    return id ? { sourceFamily: "LEGI", collection: "legal_legi_articles", documentId: id, ttlMs: LEGI_DOCUMENT_TTL_MS } : null;
  }

  if (path === "/consult/legiPart") {
    const textId = officialId(body?.textId, "LEGITEXT");
    const date = Number(body?.date);
    if (!textId || !Number.isFinite(date) || date <= 0) return null;
    return { sourceFamily: "LEGI", collection: "legal_legi_parts", documentId: `${textId}_${Math.trunc(date)}`, ttlMs: LEGI_DOCUMENT_TTL_MS };
  }

  if (path === "/list/boccsAndTexts") {
    return { sourceFamily: "BOCC", collection: "legal_bocc_search", documentId: `list_${requestHash(body)}`, ttlMs: BOCC_SEARCH_TTL_MS };
  }

  if (path === "/consult/getBoccTextPdfMetadata") {
    const id = String(body?.id ?? "").trim();
    if (!id) return null;
    return { sourceFamily: "BOCC", collection: "legal_bocc_documents", documentId: `meta_${requestHash({ id })}`, ttlMs: BOCC_DOCUMENT_TTL_MS };
  }

  if (path === "/consult/lastNJo") {
    const count = Number(body?.nbElement);
    if (!Number.isFinite(count) || count <= 0) return null;
    return { sourceFamily: "JORF", collection: "legal_jorf_feed", documentId: `last_${Math.trunc(count)}`, ttlMs: JORF_FEED_TTL_MS };
  }

  if (path === "/consult/jorfCont") {
    const id = officialId(body?.id, "JORFCONT");
    return id ? { sourceFamily: "JORF", collection: "legal_jorf_documents", documentId: `cont_${id}_${requestHash(body)}`, ttlMs: JORF_DOCUMENT_TTL_MS } : null;
  }

  if (path === "/consult/jorf") {
    const id = officialId(body?.textCid, "JORFTEXT");
    return id ? { sourceFamily: "JORF", collection: "legal_jorf_documents", documentId: id, ttlMs: JORF_DOCUMENT_TTL_MS } : null;
  }

  return null;
}

function watchSpec(path, body) {
  const spec = cacheSpec(path, body);
  if (!spec) return null;

  const watched =
    path === "/consult/kaliContIdcc" ||
    path === "/list/conventions" ||
    path === "/list/boccsAndTexts" ||
    path === "/consult/lastNJo" ||
    (path === "/search" && ["KALI", "ACCO", "CODE_DATE"].includes(String(body?.fond ?? "").trim().toUpperCase()));

  if (!watched) return null;
  return { ...spec, ...(requestScope(path, body) || {}) };
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

function nextCacheDocument({ previousData, spec, watch, path, body, payloadJson, checkedAtMs }) {
  const currentHash = payloadHash(payloadJson);
  const previousHash = typeof previousData?.payloadHash === "string"
    ? previousData.payloadHash
    : typeof previousData?.payloadJson === "string"
      ? payloadHash(previousData.payloadJson)
      : null;
  const changed = Boolean(previousHash && previousHash !== currentHash);
  const previousChangeCount = Number(previousData?.changeCount);

  const document = {
    schemaVersion: LEGAL_CACHE_SCHEMA_VERSION,
    parserVersion: LEGAL_CACHE_PARSER_VERSION,
    source: "legifrance-piste",
    sourceFamily: spec.sourceFamily,
    path,
    cachedAtMs: checkedAtMs,
    lastOfficialCheckAtMs: checkedAtMs,
    expiresAtMs: checkedAtMs + spec.ttlMs,
    payloadJson,
    payloadHash: currentHash,
    changeCount: Number.isFinite(previousChangeCount) && previousChangeCount >= 0 ? previousChangeCount : 0,
  };

  if (watch) {
    document.watchEnabled = true;
    document.requestJson = JSON.stringify(body ?? {});
    document.nextCheckAtMs = checkedAtMs + spec.ttlMs;
    if (watch.scopeType) document.scopeType = watch.scopeType;
    if (watch.scopeValue) document.scopeValue = watch.scopeValue;
  }

  if (changed) {
    document.previousPayloadHash = previousHash;
    document.changeDetectedAtMs = checkedAtMs;
    document.changeCount += 1;
  } else {
    if (typeof previousData?.previousPayloadHash === "string") {
      document.previousPayloadHash = previousData.previousPayloadHash;
    }
    if (Number.isFinite(previousData?.changeDetectedAtMs)) {
      document.changeDetectedAtMs = previousData.changeDetectedAtMs;
    }
  }

  return { document, changed };
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
  const watch = watchSpec(path, body);

  const nowMs = Number(now());
  let ref = null;
  let previousData = null;
  try {
    ref = db.collection(spec.collection).doc(spec.documentId);
    const snapshot = await ref.get();
    if (snapshot?.exists) {
      previousData = snapshot.data();
      const payload = cachePayload(previousData, nowMs);
      if (payload !== null) {
        log(logger, "info", `Legal ${spec.sourceFamily} cache hit`, { path, collection: spec.collection });
        return payload;
      }
    }
  } catch (error) {
    log(logger, "warn", `Legal ${spec.sourceFamily} cache read failed`, {
      path,
      collection: spec.collection,
      error: String(error?.message || error || "unknown"),
    });
  }

  const officialPayload = await fetchOfficial();
  const payloadJson = JSON.stringify(officialPayload);
  if (typeof payloadJson !== "string") return officialPayload;

  const checkedAtMs = Number(now());
  try {
    if (!ref) ref = db.collection(spec.collection).doc(spec.documentId);
    const { document, changed } = nextCacheDocument({
      previousData,
      spec,
      watch,
      path,
      body,
      payloadJson,
      checkedAtMs,
    });
    await ref.set(document);
    if (changed) {
      log(logger, "info", `Legal ${spec.sourceFamily} official change detected`, {
        path,
        collection: spec.collection,
        scopeType: document.scopeType || null,
        scopeValue: document.scopeValue || null,
      });
    }
  } catch (error) {
    log(logger, "warn", `Legal ${spec.sourceFamily} cache write failed`, {
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
  KALI_RULE_SEARCH_TTL_MS,
  ACCO_DOCUMENT_TTL_MS,
  ACCO_SEARCH_TTL_MS,
  LEGI_DOCUMENT_TTL_MS,
  LEGI_SEARCH_TTL_MS,
  BOCC_DOCUMENT_TTL_MS,
  BOCC_SEARCH_TTL_MS,
  JORF_DOCUMENT_TTL_MS,
  JORF_FEED_TTL_MS,
  cacheSpec,
  watchSpec,
  requestScope,
  payloadHash,
  nextCacheDocument,
  resolveWithLegalCache,
};
