"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { getApps, initializeApp, deleteApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { defaultAdminApp } = require("./firebaseRuntime");

test("default Firebase app is created and reused independently of named apps", async t => {
  const previousConfig = process.env.FIREBASE_CONFIG;
  process.env.FIREBASE_CONFIG = JSON.stringify({ projectId: "runtime-regression-test" });
  t.after(async () => {
    await Promise.all(getApps().map(app => deleteApp(app)));
    if (previousConfig === undefined) delete process.env.FIREBASE_CONFIG;
    else process.env.FIREBASE_CONFIG = previousConfig;
  });

  assert.equal(getApps().length, 0);
  const coldApp = defaultAdminApp();
  assert.equal(coldApp.name, "[DEFAULT]");
  assert.equal(defaultAdminApp(), coldApp);
  await deleteApp(coldApp);

  const namedApp = initializeApp({ projectId: "named-regression-test" }, "functions-internal");
  assert.throws(() => getFirestore(), { code: "app/no-app" });
  const app = defaultAdminApp();
  assert.equal(app.name, "[DEFAULT]");
  assert.notEqual(app, namedApp);
  assert.equal(getFirestore(app).projectId, "runtime-regression-test");
  assert.equal(getFirestore(app), getFirestore());
  assert.equal(defaultAdminApp(), app);
  assert.equal(getApps().length, 2);
});
