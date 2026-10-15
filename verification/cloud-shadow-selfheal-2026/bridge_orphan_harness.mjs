// Local, network-free verification harness for the deployed Appwrite bridge
// (service/appwrite/node-bridge/src/main.js). It drives the real function
// entrypoint with a fake Appwrite REST backend (global fetch stub) and proves:
//
//   S1 a half-provisioned shadow identity (exists, no email/phone, missing the
//      bridge identity labels) is ADOPTED and re-labelled -> storageToken 200.
//      Before the fix this returned 409 "conflict" forever (the lockout bug).
//   S2 a shadow identity that is independently sign-in capable (email set) is
//      still refused with 409 and disabled first.
//   S3 a shadow identity bound to a different source label is still refused
//      with 409 and never written to.
//   S4 a missing shadow identity is provisioned from scratch (unchanged).
//
// Run (from the repository root):
//   node verification/cloud-shadow-selfheal-2026/bridge_orphan_harness.mjs
// Red/green against the pre-fix implementation:
//   git show HEAD~1:service/appwrite/node-bridge/src/main.js > /tmp/old_main.js
//   node verification/cloud-shadow-selfheal-2026/bridge_orphan_harness.mjs /tmp/old_main.js
// (S1 must report FAIL on the old file and PASS on the fixed one.)

import crypto from "node:crypto";
import { pathToFileURL } from "node:url";

const AUTH = "6acd036f0022a495a9df";
const STORAGE = "6acd0a6800189ccc164a";
const BUCKET = "6acd11520015520ba016";
const JWT = "valid.auth.signature";
const READ = "unihubdownload";
const MANAGED = "unihubmanaged";

const target = process.argv[2] ||
  new URL("../../service/appwrite/node-bridge/src/main.js", import.meta.url).pathname;
const handler = (await import(pathToFileURL(target).href)).default;

// shadowId/sourceLabel are not exported by the function; the harness derives them
// with the exact documented scheme so seeds match what the bridge computes.
function sha256hex(text) {
  return crypto.createHash("sha256").update(text, "utf8").digest("hex");
}
const shadowId = (userId) => "u" + sha256hex(`${AUTH}:${userId}`).slice(0, 35);
const sourceLabel = (userId) => "src" + sha256hex(`${AUTH}:${userId}`).slice(0, 33);

function freshState(seed) {
  return {
    actor: {
      $id: "student", email: "student@example.test", name: "saged", status: true,
      labels: [READ], prefs: {}, $createdAt: "2026-10-12T00:00:00Z", $updatedAt: "2026-10-12T00:00:00Z",
    },
    storageUsers: seed ? { [shadowId("student")]: seed } : {},
    writes: [],
    jwtDuration: 0,
  };
}

function makeFetch(state) {
  const json = (status, payload) => ({ status, text: async () => JSON.stringify(payload) });
  return async (url, opts) => {
    const u = new URL(url);
    const project = opts.headers["X-Appwrite-Project"];
    const path = u.pathname.replace(/^\/v1/, "") || "/";
    const body = opts.body ? JSON.parse(opts.body) : undefined;
    const parts = path.split("/").filter(Boolean);
    if (project === AUTH && path === "/account") {
      return opts.headers["X-Appwrite-JWT"] === JWT ? json(200, state.actor) : json(401, { type: "general_unauthorized" });
    }
    if (project !== STORAGE) return json(400, { type: "general_unknown" });
    if (path.startsWith("/storage/buckets/")) {
      return json(200, {
        $id: BUCKET, enabled: true, fileSecurity: false,
        $permissions: [`read("label:unihubdownload")`, `create("label:unihubupload")`,
          `update("label:unihubmodify")`, `delete("label:unihubmodify")`],
      });
    }
    if (parts[0] === "users") {
      if (opts.method === "POST" && parts.length === 1) {
        if (state.storageUsers[body.userId]) return json(409, { type: "user_already_exists" });
        const row = { $id: body.userId, email: body.email || "", phone: "", name: body.name || "",
          status: true, labels: [], prefs: {}, $createdAt: "2026-10-12T00:00:00Z", $updatedAt: "2026-10-12T00:00:00Z" };
        state.storageUsers[body.userId] = row;
        return json(201, row);
      }
      const id = parts[1];
      const row = state.storageUsers[id];
      if (!row) return json(404, { type: "user_not_found" });
      if (opts.method === "POST" && parts[2] === "jwts") {
        state.jwtDuration = body.duration;
        return json(201, { jwt: "storage.project.signature" });
      }
      if (opts.method === "PUT" && parts[2] === "labels") {
        state.writes.push(`PUT labels ${id}`);
        row.labels = [...body.labels];
        return json(200, row);
      }
      if (opts.method === "PATCH" && parts[2] === "status") {
        state.writes.push(`PATCH status ${id}`);
        row.status = body.status;
        return json(200, row);
      }
      if (opts.method === "GET") return json(200, row);
    }
    return json(500, { type: "general_unknown" });
  };
}

async function runStorageToken(seed) {
  const state = freshState(seed);
  globalThis.fetch = makeFetch(state);
  process.env.APPWRITE_FUNCTION_PROJECT_ID = AUTH;
  process.env.STORAGE_API_KEY = "storage-key";
  let out = { body: "", status: 0 };
  const res = { text: (body, status) => { out = { body, status }; return out; } };
  const req = {
    method: "POST",
    headers: { "x-appwrite-user-jwt": JWT, "x-appwrite-key": "auth-key" },
    bodyText: JSON.stringify({ operation: "storageToken" }),
  };
  await handler({ req, res });
  return { out, state };
}

const technical = (labels, extra = {}) => ({
  $id: shadowId("student"), email: "", phone: "", name: "UniHub storage", status: true,
  labels, prefs: {}, $createdAt: "2026-10-12T00:00:00Z", $updatedAt: "2026-10-12T00:00:00Z", ...extra,
});

const results = [];
function check(name, condition, detail) {
  results.push({ name, pass: !!condition, detail });
}

// S1: half-provisioned orphan must be adopted, not locked out.
{
  const { out, state } = await runStorageToken(technical([]));
  const shadow = state.storageUsers[shadowId("student")];
  check("S1 orphan adopted -> 200", out.status === 200, `status=${out.status} body=${out.body}`);
  check("S1 orphan re-labelled", shadow && shadow.labels.includes(MANAGED) && shadow.labels.includes(sourceLabel("student")) && shadow.labels.includes(READ),
    `labels=${JSON.stringify(shadow && shadow.labels)}`);
  check("S1 orphan enabled + jwt minted", shadow && shadow.status === true && state.jwtDuration === 300, `status=${shadow && shadow.status} dur=${state.jwtDuration}`);
}
// S2: sign-in capable identity stays refused and is disabled first.
{
  const { out, state } = await runStorageToken(technical([MANAGED, sourceLabel("student")], { email: "intruder@example.test" }));
  const shadow = state.storageUsers[shadowId("student")];
  check("S2 sign-in capable refused -> 409 conflict", out.status === 409 && out.body.includes("conflict"), `status=${out.status} body=${out.body}`);
  check("S2 disabled before refusal", shadow.status === false && !shadow.labels.includes(READ), `status=${shadow.status} labels=${JSON.stringify(shadow.labels)}`);
}
// S3: identity bound to another source stays refused and untouched.
{
  const { out, state } = await runStorageToken(technical([MANAGED, "src" + "0".repeat(33)]));
  check("S3 foreign source refused -> 409 conflict", out.status === 409 && out.body.includes("conflict"), `status=${out.status} body=${out.body}`);
  check("S3 no writes to storage identity", state.writes.length === 0 && state.jwtDuration === 0, `writes=${JSON.stringify(state.writes)}`);
}
// S4: missing identity is provisioned from scratch (unchanged behaviour).
{
  const { out, state } = await runStorageToken(null);
  const shadow = state.storageUsers[shadowId("student")];
  check("S4 fresh provisioning -> 200", out.status === 200, `status=${out.status} body=${out.body}`);
  check("S4 labels complete", shadow && shadow.labels.includes(MANAGED) && shadow.labels.includes(sourceLabel("student")) && shadow.labels.includes(READ),
    `labels=${JSON.stringify(shadow && shadow.labels)}`);
}

let failed = 0;
for (const r of results) {
  if (!r.pass) failed++;
  console.log(`${r.pass ? "PASS" : "FAIL"}  ${r.name}  ${r.pass ? "" : "-> " + r.detail}`);
}
console.log(failed === 0 ? `ALL ${results.length} CHECKS PASSED (${target})` : `${failed}/${results.length} CHECKS FAILED (${target})`);
process.exit(failed === 0 ? 0 : 1);
