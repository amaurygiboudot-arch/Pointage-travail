"use strict";

const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { defineSecret } = require("firebase-functions/params");
const { getApps, initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const {
  isValidLegifranceBody,
  normalizeLegifranceBody,
  publicFailureMessage,
} = require("./legifranceProxy");
const { normalizeBoccRequest } = require("./boccRequest");
const { resolveWithLegalCache } = require("./legalKaliCache");
const { ensureLegalWatchRegistration } = require("./legalWatchRegistration");
const { ensureGlobalLegalWatchSeeds } = require("./legalWatchBootstrap");
const {
  ensureDerivedLegalScopeWatches,
  refreshDueLegalScopeWatches,
} = require("./legalScopeWatch");
const { recordLegalChangeAndQueue } = require("./legalChangePipeline");
const {
  dispatchPendingLegalReanalysis,
  reconcileQueuedLegalReanalysis,
} = require("./legalReanalysisDispatcher");
const {
  normalizePlanRequest,
  listReadyLegalReanalysis,
} = require("./legalReanalysisPlan");
const { invalidateDependentLegalCaches } = require("./legalCrossSourceInvalidation");
const { refreshDueLegalWatches } = require("./legalUpdateWatch");

const pisteClientId = defineSecret("PISTE_CLIENT_ID");
const pisteClientSecret = defineSecret("PISTE_CLIENT_SECRET");

const TOKEN_URL = "https://oauth.piste.gouv.fr/api/oauth/token";
const LEGIFRANCE_BASE_URL = "https://api.piste.gouv.fr/dila/legifrance/lf-engine-app";
const REQUEST_TIMEOUT_MS = 15_000;
const LEGAL_WATCH_BATCH_SIZE = 12;
const LEGAL_SCOPE_WATCH_BATCH_SIZE = 6;
const LEGAL_REANALYSIS_BATCH_SIZE = 8;
const ALLOWED_PATHS = new Set([
  "/search",
  "/consult/acco",
  "/consult/kaliContIdcc",
  "/consult/kaliArticle",
  "/consult/kaliText",
  "/list/conventions",
  "/consult/getArticle",
  "/consult/legiPart",
  "/list/boccsAndTexts",
  "/consult/getBoccTextPdfMetadata",
  "/consult/lastNJo",
  "/consult/jorfCont",
  "/consult/jorf",
]);

let cachedToken = null;
let cachedTokenExpiresAt = 0;
let firestoreDb = null;
let firestoreInitAttempted = false;

class UpstreamError extends Error {
  constructor(stage, status = 0, upstreamBody = "") {
    super(`${stage}${status ? ` ${status}` : ""}`);
    this.stage = stage;
    this.status = status;
    this.upstreamBody = upstreamBody;
  }
}

function safeUpstreamCode(value) {
  try {
    const parsed = JSON.parse(String(value || ""));
    const candidate = parsed.error || parsed.code || parsed.status || "";
    return String(candidate).replace(/[^a-zA-Z0-9_.-]/g, "").slice(0, 80);
  } catch (_error) {
    return "";
  }
}

async function readJsonResponse(response, stage) {
  const text = await response.text();
  if (!response.ok) throw new UpstreamError(stage, response.status, text);
  try {
    return JSON.parse(text);
  } catch (_error) {
    throw new UpstreamError("response", response.status, "invalid-json");
  }
}

function legalCacheDb() {
  if (firestoreInitAttempted) return firestoreDb;
  firestoreInitAttempted = true;
  try {
    const app = getApps().length ? getApps()[0] : initializeApp();
    firestoreDb = getFirestore(app);
  } catch (error) {
    console.warn("Legal cache Firestore init failed", {
      error: String(error?.message || error || "unknown"),
    });
    firestoreDb = null;
  }
  return firestoreDb;
}

async function pisteAccessToken() {
  if (cachedToken && Date.now() < cachedTokenExpiresAt) return cachedToken;

  const clientId = String(pisteClientId.value() || "").trim();
  const clientSecret = String(pisteClientSecret.value() || "").trim();
  if (!clientId || !clientSecret) throw new UpstreamError("configuration");

  const form = new URLSearchParams({
    grant_type: "client_credentials",
    client_id: clientId,
    client_secret: clientSecret,
    scope: "openid",
  });
  const response = await fetch(TOKEN_URL, {
    method: "POST",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body: form.toString(),
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
  });
  const json = await readJsonResponse(response, "oauth");
  if (typeof json.access_token !== "string" || !json.access_token) {
    throw new UpstreamError("response", response.status, "access-token-absent");
  }

  const expiresInSeconds = Number(json.expires_in);
  const usableLifetimeMs = Math.max(30, Number.isFinite(expiresInSeconds) ? expiresInSeconds - 60 : 240) * 1000;
  cachedToken = json.access_token;
  cachedTokenExpiresAt = Date.now() + usableLifetimeMs;
  return cachedToken;
}

async function fetchOfficialLegifrance(path, body) {
  const token = await pisteAccessToken();
  const response = await fetch(`${LEGIFRANCE_BASE_URL}${path}`, {
    method: "POST",
    headers: {
      Accept: "application/json",
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
  });
  return readJsonResponse(response, "api");
}

async function handleLegalChange(db, change) {
  await recordLegalChangeAndQueue({ db, change, logger: console });
  await invalidateDependentLegalCaches({ db, change, logger: console });
}

exports.legifranceRequest = onCall(
  { secrets: [pisteClientId, pisteClientSecret], timeoutSeconds: 30 },
  async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Connexion HoraTrack requise.");

    const path = typeof request.data?.path === "string" ? request.data.path : "";
    if (!ALLOWED_PATHS.has(path)) {
      throw new HttpsError("invalid-argument", "Route Légifrance non autorisée.");
    }
    if (!isValidLegifranceBody(path, request.data?.body)) {
      throw new HttpsError("invalid-argument", "Paramètres Légifrance invalides.");
    }

    try {
      const body = path === "/list/boccsAndTexts"
        ? normalizeBoccRequest(request.data?.body)
        : normalizeLegifranceBody(path, request.data?.body);
      const db = legalCacheDb();
      const result = await resolveWithLegalCache({
        db,
        path,
        body,
        logger: console,
        fetchOfficial: () => fetchOfficialLegifrance(path, body),
      });

      await ensureLegalWatchRegistration({
        db,
        path,
        body,
        logger: console,
      });
      await ensureDerivedLegalScopeWatches({
        db,
        path,
        body,
        logger: console,
      });
      return result;
    } catch (error) {
      const isTimeout = error?.name === "TimeoutError" || error?.name === "AbortError";
      const stage = isTimeout ? "timeout" : error instanceof UpstreamError ? error.stage : "network";
      const status = error instanceof UpstreamError ? error.status : 0;
      console.error("Légifrance proxy error", {
        stage,
        status,
        upstreamCode: error instanceof UpstreamError ? safeUpstreamCode(error.upstreamBody) : "",
      });
      throw new HttpsError("unavailable", publicFailureMessage(stage, status, path), { stage, status });
    }
  }
);

exports.legalReanalysisPlan = onCall(
  { timeoutSeconds: 15 },
  async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Connexion HoraTrack requise.");
    const db = legalCacheDb();
    if (!db) throw new HttpsError("unavailable", "Veille juridique momentanément indisponible.");

    const scope = normalizePlanRequest(request.data);
    try {
      const jobs = await listReadyLegalReanalysis({
        db,
        idcc: scope.idcc,
        siret: scope.siret,
      });
      return {
        schemaVersion: 1,
        generatedAtMs: Date.now(),
        jobs,
      };
    } catch (error) {
      console.warn("Legal reanalysis plan read failed", {
        hasIdcc: Boolean(scope.idcc),
        hasSiret: Boolean(scope.siret),
        error: String(error?.message || error || "unknown"),
      });
      throw new HttpsError("unavailable", "Plan de mise à jour juridique indisponible.");
    }
  }
);

exports.legalUpdateWatch = onSchedule(
  {
    schedule: "every 6 hours",
    timeZone: "Europe/Paris",
    secrets: [pisteClientId, pisteClientSecret],
    timeoutSeconds: 300,
  },
  async () => {
    const db = legalCacheDb();
    if (!db) {
      console.warn("Legal update watch skipped: Firestore unavailable");
      return;
    }

    const bootstrap = await ensureGlobalLegalWatchSeeds({ db, logger: console });
    const onChange = (change) => handleLegalChange(db, change);
    const dispatchBefore = await dispatchPendingLegalReanalysis({
      db,
      logger: console,
      batchSize: LEGAL_REANALYSIS_BATCH_SIZE,
    });
    const cacheWatches = await refreshDueLegalWatches({
      db,
      fetchOfficial: fetchOfficialLegifrance,
      onChange,
      logger: console,
      batchSize: LEGAL_WATCH_BATCH_SIZE,
    });
    const scopeWatches = await refreshDueLegalScopeWatches({
      db,
      fetchOfficial: fetchOfficialLegifrance,
      onChange,
      logger: console,
      batchSize: LEGAL_SCOPE_WATCH_BATCH_SIZE,
    });
    const reconcileAfterRefresh = await reconcileQueuedLegalReanalysis({
      db,
      logger: console,
      batchSize: LEGAL_REANALYSIS_BATCH_SIZE,
    });
    const dispatchAfter = await dispatchPendingLegalReanalysis({
      db,
      logger: console,
      batchSize: LEGAL_REANALYSIS_BATCH_SIZE,
    });

    let followUpCacheWatches = null;
    if (dispatchAfter.queuedWatches > 0) {
      followUpCacheWatches = await refreshDueLegalWatches({
        db,
        fetchOfficial: fetchOfficialLegifrance,
        onChange,
        logger: console,
        batchSize: Math.min(LEGAL_WATCH_BATCH_SIZE, dispatchAfter.queuedWatches),
      });
    }

    const reconcileFinal = await reconcileQueuedLegalReanalysis({
      db,
      logger: console,
      batchSize: LEGAL_REANALYSIS_BATCH_SIZE,
    });
    const dispatchFinal = followUpCacheWatches
      ? await dispatchPendingLegalReanalysis({
        db,
        logger: console,
        batchSize: LEGAL_REANALYSIS_BATCH_SIZE,
      })
      : null;

    console.info("Legal update watch complete", {
      bootstrap,
      dispatchBefore,
      cacheWatches,
      scopeWatches,
      reconcileAfterRefresh,
      dispatchAfter,
      followUpCacheWatches,
      reconcileFinal,
      dispatchFinal,
    });
  }
);