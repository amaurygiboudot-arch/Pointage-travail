"use strict";

const { applicationDefault, initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");

const revision = String(process.env.BACKEND_REVISION || "").trim();
const runId = String(process.env.BACKEND_RUN_ID || "").trim();
const projectId = String(process.env.FIREBASE_PROJECT_ID || "pointage-travail").trim();

if (!revision) {
  console.error("BACKEND_REVISION manquante");
  process.exit(1);
}

const title = "Mise à jour Firebase HoraTrack";
const message = "Les services Firebase d’HoraTrack ont été mis à jour avec succès.";
const deployedAtMs = Date.now();

async function main() {
  const app = initializeApp({
    credential: applicationDefault(),
    projectId,
  });
  const db = getFirestore(app);
  await db.collection("system_status").doc("backend_deploy").set({
    revision,
    deployedAtMs,
    runId,
    title,
    message,
    source: "github-actions",
  });
  console.log("Révision backend publiée dans Firestore", revision);

  try {
    const response = await getMessaging(app).send({
      topic: "horatrack_backend_updates",
      data: {
        kind: "backend_update",
        revision,
        deployedAtMs: String(deployedAtMs),
        title,
        body: message,
      },
      android: {
        priority: "high",
      },
    });
    console.log("Notification backend Firebase envoyée", response);
  } catch (error) {
    // Le document Firestore reste la source de rattrapage au prochain lancement.
    console.warn("Push backend non envoyé; le contrôle au lancement prendra le relais", String(error?.message || error));
  }
}

main().catch((error) => {
  console.error("Publication de la révision backend impossible", error);
  process.exit(1);
});
