"use strict";

const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");
const {
  isValidLegifranceBody,
  normalizeLegifranceBody,
  publicFailureMessage,
} = require("./legifranceProxy");
const { normalizeBoccRequest } = require("./boccRequest");

const pisteClientId = defineSecret("PISTE_CLIENT_ID");
const pisteClientSecret = defineSecret("PISTE_CLIENT_SECRET");

const TOKEN_URL = "https://oauth.piste.gouv.fr/api/oauth/token";
const LEGIFRANCE_BASE_URL = "https://api.piste.gouv.fr/dila/legifrance/lf-engine-app";
const REQUEST_TIMEOUT_MS = 15_000;
const ALLOWED_PATHS = new Set([
  "/search",
  "/consult/acco",
  "/consult/kaliContIdcc",
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
      const token = await pisteAccessToken();
      const body = path === "/list/boccsAndTexts"
        ? normalizeBoccRequest(request.data?.body)
        : normalizeLegifranceBody(path, request.data?.body);
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
      return await readJsonResponse(response, "api");
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
