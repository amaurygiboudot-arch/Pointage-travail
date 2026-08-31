const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");

const pisteClientId = defineSecret("PISTE_CLIENT_ID");
const pisteClientSecret = defineSecret("PISTE_CLIENT_SECRET");

const TOKEN_URL = "https://oauth.piste.gouv.fr/api/oauth/token";
const LEGIFRANCE_BASE_URL = "https://api.piste.gouv.fr/dila/legifrance/lf-engine-app";

async function pisteAccessToken() {
  const credentials = Buffer.from(`${pisteClientId.value()}:${pisteClientSecret.value()}`).toString("base64");
  const response = await fetch(TOKEN_URL, {
    method: "POST",
    headers: {
      Authorization: `Basic ${credentials}`,
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body: "grant_type=client_credentials",
  });
  if (!response.ok) throw new Error(`PISTE OAuth ${response.status}`);
  const json = await response.json();
  if (!json.access_token) throw new Error("PISTE OAuth: jeton absent");
  return json.access_token;
}

exports.legifranceRequest = onCall(
  { secrets: [pisteClientId, pisteClientSecret] },
  async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Connexion HoraTrack requise.");

    const path = typeof request.data?.path === "string" ? request.data.path : "";
    const body = request.data?.body;
    if (!path.startsWith("/consult/acco") && !path.startsWith("/search")) {
      throw new HttpsError("invalid-argument", "Route Légifrance non autorisée.");
    }

    try {
      const token = await pisteAccessToken();
      const response = await fetch(`${LEGIFRANCE_BASE_URL}${path}`, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${token}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify(body || {}),
      });
      const text = await response.text();
      if (!response.ok) throw new Error(`Légifrance ${response.status}: ${text.slice(0, 300)}`);
      return JSON.parse(text);
    } catch (error) {
      console.error("Légifrance proxy error", error);
      throw new HttpsError("internal", "Impossible d’interroger Légifrance.");
    }
  }
);
