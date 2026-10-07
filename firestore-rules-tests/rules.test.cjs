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
  runTransaction,
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


// Manual comfort snapshots use the existing owner-scoped backup collection.
const sharedComfortPayload = JSON.stringify({
  format: 'agkgmg.comfort', version: 1,
  highContrast: true, reduceMotion: false, readerScale: 2.25,
});
function comfortRef(db, uid = 'alice') {
  return doc(db, `users/${uid}/app_backup/comfort_shared_v1`);
}
async function writeExpectedComfort(db, expectedRevision, payload = sharedComfortPayload) {
  const reference = comfortRef(db);
  return runTransaction(db, async (transaction) => {
    const current = await transaction.get(reference);
    const revision = current.exists() ? current.data().revision : 0;
    if (revision !== expectedRevision) throw new Error('stale comfort revision');
    transaction.set(reference, {
      schemaVersion: 1, revision: revision + 1, deleted: payload === '',
      payload, updatedAt: serverTimestamp(),
    });
    return revision + 1;
  });
}

test('comfort account backup is private across authenticated and guest clients', async () => {
  const alice = testEnv.authenticatedContext('alice').firestore();
  await assertSucceeds(writeExpectedComfort(alice, 0));
  const value = await assertSucceeds(getDoc(comfortRef(alice)));
  assert.equal(value.data().payload, sharedComfortPayload);
  for (const other of [testEnv.authenticatedContext('bob').firestore(), testEnv.unauthenticatedContext().firestore()]) {
    await assertFails(getDoc(comfortRef(other)));
    await assertFails(setDoc(comfortRef(other), { deleted: true }));
    await assertFails(deleteDoc(comfortRef(other)));
  }
});

test('two simultaneous comfort clients cannot overwrite the same observed revision', async () => {
  const phone = testEnv.authenticatedContext('alice', { device: 'phone' }).firestore();
  const tablet = testEnv.authenticatedContext('alice', { device: 'tablet' }).firestore();
  const outcomes = await Promise.allSettled([
    writeExpectedComfort(phone, 0), writeExpectedComfort(tablet, 0),
  ]);
  assert.equal(outcomes.filter((value) => value.status === 'fulfilled').length, 1);
  assert.equal(outcomes.filter((value) => value.status === 'rejected').length, 1);
  const saved = await getDoc(comfortRef(phone));
  assert.equal(saved.data().revision, 1);
  assert.equal(saved.data().payload, sharedComfortPayload);
});

test('comfort deletion keeps its revision and rejects stale resurrection', async () => {
  const phone = testEnv.authenticatedContext('alice', { device: 'phone' }).firestore();
  const tablet = testEnv.authenticatedContext('alice', { device: 'tablet' }).firestore();
  await writeExpectedComfort(phone, 0);
  await writeExpectedComfort(tablet, 1, '');
  await assert.rejects(writeExpectedComfort(phone, 1), /stale comfort revision/);
  const deleted = (await getDoc(comfortRef(phone))).data();
  assert.equal(deleted.deleted, true);
  assert.equal(deleted.payload, '');
  assert.equal(deleted.revision, 2);
  await writeExpectedComfort(phone, 2);
  assert.equal((await getDoc(comfortRef(tablet))).data().revision, 3);
});
