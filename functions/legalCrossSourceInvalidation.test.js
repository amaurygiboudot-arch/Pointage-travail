"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const {
  invalidateKaliForIdcc,
  invalidateDependentLegalCaches,
} = require("./legalCrossSourceInvalidation");

function makeDoc(id, data = {}, exists = true) {
  const state = { ...data };
  return {
    id,
    state,
    ref: {
      async get() {
        return { exists, data: () => ({ ...state }) };
      },
      async set(value, options) {
        assert.equal(options?.merge, true);
        Object.assign(state, value);
      },
    },
    data: () => ({ ...state }),
  };
}

function fakeDb(docs) {
  const byId = new Map(docs.map((doc) => [doc.id, doc]));
  return {
    collection(name) {
      assert.equal(name, "legal_kali_search");
      return {
        doc(id) {
          return byId.get(id)?.ref || makeDoc(id, {}, false).ref;
        },
        where(field, operator, value) {
          assert.equal(field, "scopeValue");
          assert.equal(operator, "==");
          return {
            limit(max) {
              return {
                async get() {
                  return {
                    docs: docs
                      .filter((doc) => doc.state.scopeValue === value)
                      .slice(0, max),
                  };
                },
              };
            },
          };
        },
      };
    },
  };
}

const quietLogger = { info() {}, warn() {} };

test("un changement BOCC IDCC 0292 expire le conteneur et les recherches KALI du même IDCC", async () => {
  const nowMs = 1_800_000_000_000;
  const direct = makeDoc("idcc_292", {
    scopeType: "IDCC",
    scopeValue: "0292",
    expiresAtMs: nowMs + 999_999,
    nextCheckAtMs: nowMs + 999_999,
  });
  const search = makeDoc("search_x", {
    scopeType: "IDCC",
    scopeValue: "0292",
    expiresAtMs: nowMs + 999_999,
    nextCheckAtMs: nowMs + 999_999,
  });
  const other = makeDoc("search_other", {
    scopeType: "IDCC",
    scopeValue: "2148",
    expiresAtMs: nowMs + 999_999,
  });

  const count = await invalidateDependentLegalCaches({
    db: fakeDb([direct, search, other]),
    change: { sourceFamily: "BOCC", scopeType: "IDCC", scopeValue: "0292" },
    now: () => nowMs,
    logger: quietLogger,
  });

  assert.equal(count, 2);
  for (const doc of [direct, search]) {
    assert.equal(doc.state.expiresAtMs, nowMs);
    assert.equal(doc.state.nextCheckAtMs, nowMs);
    assert.equal(doc.state.invalidationReason, "BOCC_CHANGE");
  }
  assert.notEqual(other.state.expiresAtMs, nowMs);
});

test("l'invalidation normalise 292 en 0292 et ne touche jamais un autre périmètre", async () => {
  const nowMs = 1_800_000_000_000;
  const direct = makeDoc("idcc_292", { expiresAtMs: nowMs + 1 });
  const count = await invalidateKaliForIdcc({
    db: fakeDb([direct]),
    idcc: "292",
    now: () => nowMs,
    logger: quietLogger,
  });
  assert.equal(count, 1);
  assert.equal(direct.state.expiresAtMs, nowMs);
});

test("JORF, ACCO et LEGI n'expirent pas globalement KALI sans périmètre conventionnel certain", async () => {
  const db = fakeDb([]);
  for (const sourceFamily of ["JORF", "ACCO", "LEGI", "KALI"]) {
    assert.equal(await invalidateDependentLegalCaches({
      db,
      change: { sourceFamily, scopeType: "GLOBAL", scopeValue: "FRANCE" },
      logger: quietLogger,
    }), 0);
  }
});
