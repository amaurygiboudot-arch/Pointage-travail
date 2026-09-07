"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const {
  normalizeBackendRevision,
  readBackendRevision,
} = require("./backendRevision");

test("normalise une révision backend sans exposer de données inutiles", () => {
  assert.deepEqual(
    normalizeBackendRevision({
      revision: "abc123",
      deployedAtMs: 1234,
      runId: "42",
      title: "Firebase mis à jour",
      message: "Déploiement terminé",
      secret: "jamais renvoyé",
    }),
    {
      schemaVersion: 1,
      available: true,
      revision: "abc123",
      deployedAtMs: 1234,
      runId: "42",
      title: "Firebase mis à jour",
      message: "Déploiement terminé",
    }
  );
});

test("une révision absente est signalée sans inventer un déploiement", () => {
  assert.equal(normalizeBackendRevision({}).available, false);
});

test("lit uniquement le document public de statut backend", async () => {
  const db = {
    collection(name) {
      assert.equal(name, "system_status");
      return {
        doc(id) {
          assert.equal(id, "backend_deploy");
          return {
            async get() {
              return {
                exists: true,
                data: () => ({ revision: "r1", deployedAtMs: 99 }),
              };
            },
          };
        },
      };
    },
  };
  const result = await readBackendRevision({ db });
  assert.equal(result.available, true);
  assert.equal(result.revision, "r1");
  assert.equal(result.deployedAtMs, 99);
});
