"use strict";

const { applicationDefault, initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");
const { buildBackendReleaseNotice } = require("../backendReleaseNotice");

async function main() {
  const revision = String(process.env.BACKEND_RELEASE_REVISION || process.env.GITHUB_SHA || "").trim();
  const message = String(process.env.BACKEND_RELEASE_MESSAGE || "").trim();
  const deployedAtMs = Date.now();
  const projectId = String(process.env.GCLOUD_PROJECT || process.env.GOOGLE_CLOUD_PROJECT || "pointage-travail").trim();

  const app = initializeApp({
    credential: applicationDefault(),
    projectId,
  });
  const notice = buildBackendReleaseNotice({ revision, message, deployedAtMs });

  await getFirestore(app)
    .collection("system")
    .doc("backend_release")
    .set(notice.document, { merge: false });

  const messageId = await getMessaging(app).send(notice.message);
  console.log("Backend release notice published", {
    revision: notice.document.revision.slice(0, 10),
    deployedAtMs: notice.document.deployedAtMs,
    messageId,
  });
}

main().catch((error) => {
  console.error("Backend release notice failed", {
    error: String(error?.message || error || "unknown"),
  });
  process.exitCode = 1;
});
