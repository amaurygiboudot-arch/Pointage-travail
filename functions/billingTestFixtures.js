"use strict";
const assert = require("node:assert/strict");
function memoryDb() {
  const rows = new Map(); let chain = Promise.resolve();
  const ref = path => ({ path, collection: name => collection(path + "/" + name), async get() { return snap(path); }, async set(data, options) { rows.set(path, options?.merge ? { ...rows.get(path), ...data } : data); } });
  const snap = path => ({ exists: rows.has(path), data: () => rows.get(path), id: path.split("/").at(-1), ref: ref(path) });
  const collection = path => ({ doc: id => ref(path + "/" + id), where: (field, op, value) => ({ async get() { return { docs: [...rows].filter(([key, data]) => key.startsWith(path + "/") && data[field] === value).map(([key]) => snap(key)) }; } }) });
  return { collection, rows, async runTransaction(fn) { const prior = chain; let release; chain = new Promise(resolve => { release = resolve; }); await prior; try { const pending = []; const result = await fn({ get: async r => snap(r.path), create: (r, d) => { assert(!rows.has(r.path)); pending.push([r.path, d]); }, set: (r, d) => pending.push([r.path, { ...rows.get(r.path), ...d }]), update: (r, d) => pending.push([r.path, { ...rows.get(r.path), ...d }]) }); for (const [path, data] of pending) rows.set(path, { ...rows.get(path), ...data }); return result; } finally { release(); } } };
}
module.exports = { memoryDb };
