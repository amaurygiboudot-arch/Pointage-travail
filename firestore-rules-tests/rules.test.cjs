const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const assert = require('node:assert/strict');
const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require('@firebase/rules-unit-testing');
const {
  deleteDoc,
  doc,
  getDoc,
  serverTimestamp,
  setDoc,
  updateDoc,
} = require('firebase/firestore');

const projectId = 'demo-horatrack';
let testEnv;

test.before(async () => {
  const rules = fs.readFileSync(path.resolve(__dirname, '../firestore.rules'), 'utf8');
  testEnv = await initializeTestEnvironment({
    projectId,
    firestore: { rules },
  });
});

test.after(async () => {
  await testEnv?.cleanup();
});

test.beforeEach(async () => {
  await testEnv.clearFirestore();
});

function snapshotRef(uid = 'alice', snapshotId = 'device1234567890_snapshot1234567890') {
  const db = testEnv.authenticatedContext(uid).firestore();
  return doc(db, `users/${uid}/game_saves/atelier/snapshots/${snapshotId}`);
}

function validSnapshot(overrides = {}) {
  return {
    schemaVersion: 1,
    campaignId: 'atelier',
    revision: 1,
    parentSnapshotIds: [],
    deviceId: '0123456789abcdef0123456789abcdef',
    payload: '{"schemaVersion":1}',
    updatedAt: serverTimestamp(),
    ...overrides,
  };
}

test('a signed-in player may append and read their own valid snapshot', async () => {
  const ref = snapshotRef();
  await assertSucceeds(setDoc(ref, validSnapshot()));
  const snapshot = await assertSucceeds(getDoc(ref));
  assert.equal(snapshot.exists(), true);
  assert.equal(snapshot.data().campaignId, 'atelier');
});

test('game saves are private to the matching signed-in UID', async () => {
  const ownRef = snapshotRef();
  await assertSucceeds(setDoc(ownRef, validSnapshot()));

  const unauthenticated = testEnv.unauthenticatedContext().firestore();
  await assertFails(getDoc(doc(unauthenticated, ownRef.path)));
  await assertFails(setDoc(
    doc(testEnv.authenticatedContext('bob').firestore(), ownRef.path),
    validSnapshot(),
  ));
  await assertFails(getDoc(doc(testEnv.authenticatedContext('bob').firestore(), ownRef.path)));
});

test('snapshot history is append-only', async () => {
  const ref = snapshotRef();
  await assertSucceeds(setDoc(ref, validSnapshot()));
  await assertFails(updateDoc(ref, { revision: 2 }));
  await assertFails(deleteDoc(ref));
});

test('malformed, oversized, or unexpected snapshot fields are rejected', async () => {
  const invalidDocuments = [
    validSnapshot({ extra: true }),
    validSnapshot({ campaignId: 'commerce' }),
    validSnapshot({ schemaVersion: 2 }),
    validSnapshot({ revision: 0 }),
    validSnapshot({ parentSnapshotIds: Array(9).fill('parent') }),
    validSnapshot({ deviceId: 'short' }),
    validSnapshot({ payload: 'x'.repeat(500_001) }),
    validSnapshot({ updatedAt: new Date() }),
  ];

  for (const [index, data] of invalidDocuments.entries()) {
    await assertFails(setDoc(snapshotRef('alice', `invalid_${index}`), data));
  }
});
