"use strict";

const BACKEND_UPDATE_TOPIC = "horatrack_backend_updates";

function cleanMessage(value) {
  return String(value || "")
    .replace(/[\r\n]+/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 240);
}

function normalizeRevision(value) {
  const revision = String(value || "").trim();
  if (!/^[a-f0-9]{7,64}$/i.test(revision)) {
    throw new Error("Invalid backend release revision");
  }
  return revision.toLowerCase();
}

function buildBackendReleaseNotice({ revision, message, deployedAtMs = Date.now() }) {
  const normalizedRevision = normalizeRevision(revision);
  cleanMessage(message); // valide/nettoie l'entrée sans l'exposer aux clients.
  const timestamp = Number(deployedAtMs);
  if (!Number.isFinite(timestamp) || timestamp <= 0) {
    throw new Error("Invalid backend release timestamp");
  }

  const title = "Mise à jour Firebase terminée";
  const body = "Le backend Firebase HoraTrack vient d'être mis à jour avec succès.";

  return {
    document: {
      schemaVersion: 1,
      revision: normalizedRevision,
      deployedAtMs: Math.trunc(timestamp),
      title,
      body,
    },
    message: {
      topic: BACKEND_UPDATE_TOPIC,
      data: {
        kind: "backend_update",
        revision: normalizedRevision,
        deployedAtMs: String(Math.trunc(timestamp)),
        title,
        body,
      },
      android: {
        priority: "high",
        ttl: 24 * 60 * 60 * 1000,
      },
    },
  };
}

module.exports = {
  BACKEND_UPDATE_TOPIC,
  buildBackendReleaseNotice,
  cleanMessage,
  normalizeRevision,
};
