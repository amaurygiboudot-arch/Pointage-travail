"use strict";

const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { getApps, initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const existingFunctions = require("./index");
const { readBackendRevision } = require("./backendRevision");

Object.assign(exports, existingFunctions);

exports.backendRevision = onCall({ timeoutSeconds: 10 }, async () => {
  try {
    const app = getApps().length ? getApps()[0] : initializeApp();
    return await readBackendRevision({ db: getFirestore(app) });
  } catch (error) {
    console.warn("Backend revision read failed", {
      error: String(error?.message || error || "unknown"),
    });
    throw new HttpsError("unavailable", "État du backend Firebase indisponible.");
  }
});
