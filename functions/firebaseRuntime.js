"use strict";

const { getApps, initializeApp } = require("firebase-admin/app");

function defaultAdminApp() {
  return getApps().find(app => app.name === "[DEFAULT]") || initializeApp();
}

module.exports = { defaultAdminApp };
