// Appwrite Function entrypoint (Node.js runtime).
//
// Faithful behavioural port of service/appwrite/auth-bridge/*.kt (Kotlin).
// Ported to Node.js on 2026-10-15 because this Appwrite Cloud project/region
// (nyc.cloud.appwrite.io) does NOT offer a Kotlin/JVM function runtime —
// verified live against GET /v1/functions/runtimes (32 runtimes returned,
// none Kotlin or Java). The Kotlin source is kept in
// service/appwrite/auth-bridge/ as reference and for local unit testing;
// THIS file is the one actually deployed to Appwrite Cloud.
//
// Deploy in the AUTH project only. Never enable request/response execution
// logging: responses can carry a storage JWT and admin requests can carry a
// new password.
//
// No admin/server API key is ever hard-coded here:
// - the AUTH project key is the per-execution Dynamic API Key Appwrite
//   injects in the `x-appwrite-key` request header (configured via function
//   "scopes"),
// - the STORAGE project key is read from the environment variable
//   STORAGE_API_KEY, which must be configured as a secret Function variable
//   in the Appwrite Console/API — never committed to source control.

import crypto from "node:crypto";

const ENDPOINT = "https://nyc.cloud.appwrite.io/v1";
const AUTH_PROJECT = "6acd036f0022a495a9df";
const STORAGE_PROJECT = "6acd0a6800189ccc164a";
const BUCKET = "6acd11520015520ba016";
const TOKEN_SECONDS = 300;

const ADMIN = "admin";
const READ = "unihubdownload";
const CREATE = "unihubupload";
const MODIFY = "unihubmodify";
const MANAGED = "unihubmanaged";

const BUCKET_PERMISSIONS = new Set([
  `read("label:${READ}")`,
  `create("label:${CREATE}")`,
  `update("label:${MODIFY}")`,
  `delete("label:${MODIFY}")`,
]);

function digest(text) {
  return crypto.createHash("sha256").update(text, "utf8").digest("hex");
}
function shadowId(userId) {
  return "u" + digest(`${AUTH_PROJECT}:${userId}`).slice(0, 35);
}
function sourceLabel(userId) {
  return "src" + digest(`${AUTH_PROJECT}:${userId}`).slice(0, 33);
}
function validId(id) {
  return typeof id === "string" && /^[A-Za-z0-9][A-Za-z0-9._-]{0,35}$/.test(id);
}
function validEmail(email) {
  if (typeof email !== "string" || email.length === 0 || email.length > 320) return false;
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return false;
  for (const ch of email) {
    const code = ch.codePointAt(0);
    if (code < 0x20 || code === 0x7f) return false;
  }
  return true;
}
function hasControlChars(value) {
  for (const ch of value) {
    const code = ch.codePointAt(0);
    if (code < 0x20 || code === 0x7f) return true;
  }
  return false;
}
function setsEqual(a, b) {
  if (a.size !== b.size) return false;
  for (const v of a) if (!b.has(v)) return false;
  return true;
}

class BridgeFailure extends Error {
  constructor(status, code) {
    super(code);
    this.status = status;
    this.code = code;
  }
}
class UpstreamFailure extends Error {
  constructor(status, type) {
    super("upstream_request_failed");
    this.status = status;
    this.type = type || "";
  }
}

/** Raw REST client. Mirrors AppwriteRestApi.kt exactly (same headers, same limits). */
function makeApi(authKey, storageKey) {
  async function request(project, method, path, { body, queries = [], key, jwt } = {}) {
    if (!/^(GET|POST|PUT|PATCH|DELETE)$/.test(method)) throw new BridgeFailure(503, "bridge_configuration");
    if (!/^\/[A-Za-z0-9/._-]*$/.test(path)) throw new BridgeFailure(503, "bridge_configuration");
    const qs = queries.length ? "?" + queries.map((q) => "queries%5B%5D=" + encodeURIComponent(q)).join("&") : "";
    const headers = {
      "X-Appwrite-Project": project,
      "Content-Type": "application/json",
      Accept: "application/json",
    };
    if (jwt) headers["X-Appwrite-JWT"] = jwt;
    if (key) headers["X-Appwrite-Key"] = key;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 20_000);
    let resp;
    try {
      resp = await fetch(ENDPOINT + path + qs, {
        method,
        headers,
        body: body !== undefined ? JSON.stringify(body) : undefined,
        redirect: "manual",
        signal: controller.signal,
      });
    } finally {
      clearTimeout(timeout);
    }
    const text = await resp.text();
    if (Buffer.byteLength(text, "utf8") > 1024 * 1024) throw new BridgeFailure(502, "upstream_response_limit");
    if (!(resp.status >= 200 && resp.status < 300)) {
      let type = "";
      try {
        type = JSON.parse(text).type || "";
      } catch {
        /* ignore non-JSON error bodies */
      }
      throw new UpstreamFailure(resp.status, type);
    }
    return text.trim().length === 0 ? {} : JSON.parse(text);
  }

  return {
    // Must use X-Appwrite-JWT ONLY, never an admin key, against the AUTH project /account.
    async account(jwt) {
      try {
        return await request(AUTH_PROJECT, "GET", "/account", { jwt });
      } catch (e) {
        if (e instanceof UpstreamFailure && (e.status === 401 || e.status === 403)) {
          throw new BridgeFailure(401, "unauthorized");
        }
        throw e;
      }
    },
    auth(method, path, body, queries = []) {
      return request(AUTH_PROJECT, method, path, { body, queries, key: authKey });
    },
    storage(method, path, body) {
      return request(STORAGE_PROJECT, method, path, { body, key: storageKey });
    },
  };
}

function labelsOf(user) {
  return new Set(Array.isArray(user.labels) ? user.labels.filter((v) => typeof v === "string") : []);
}
function publicProfile(user) {
  const out = {};
  for (const key of ["$id", "email", "name", "status", "labels", "$createdAt", "$updatedAt"]) out[key] = user[key];
  return out;
}
function query(method, attribute, values = []) {
  const obj = { method, values };
  if (attribute) obj.attribute = attribute;
  return JSON.stringify(obj);
}
function checkHierarchy(labelSet) {
  if (
    (labelSet.has(CREATE) && !labelSet.has(READ)) ||
    (labelSet.has(MODIFY) && (!labelSet.has(READ) || !labelSet.has(CREATE)))
  ) {
    throw new BridgeFailure(400, "invalid_permissions");
  }
}
function effectiveLabels(user) {
  const assigned = labelsOf(user);
  if (assigned.has(ADMIN)) return new Set([READ, CREATE, MODIFY]);
  const effective = new Set([...assigned].filter((v) => [READ, CREATE, MODIFY].includes(v)));
  checkHierarchy(effective);
  return effective;
}
function permissionLabels(json) {
  const keys = Object.keys(json || {});
  const expected = ["canDownload", "canUpload", "canModify"];
  if (keys.length !== 3 || !expected.every((k) => keys.includes(k)) || expected.some((k) => typeof json[k] !== "boolean")) {
    throw new BridgeFailure(400, "invalid_permissions");
  }
  const result = new Set();
  if (json.canDownload) result.add(READ);
  if (json.canUpload) result.add(CREATE);
  if (json.canModify) result.add(MODIFY);
  checkHierarchy(result);
  return result;
}
function requirePassword(input) {
  const p = input.password;
  if (typeof p !== "string" || p.length < 10 || p.length > 256 || p.trim().length === 0) {
    throw new BridgeFailure(400, "invalid_password");
  }
  return p;
}
function requireActiveActor(actor) {
  if (actor.status !== true || !validId(actor.$id) || !validEmail(actor.email || "")) {
    throw new BridgeFailure(401, "unauthorized");
  }
}

class BridgeService {
  constructor(api) {
    this.api = api;
  }

  async execute(jwt, input) {
    if (typeof jwt !== "string" || jwt.length < 16 || jwt.length > 8192 || !/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(jwt)) {
      throw new BridgeFailure(401, "unauthorized");
    }
    const actor = await this.api.account(jwt); // Cryptographic/session verification happens in Appwrite, not in JSON decoding.
    requireActiveActor(actor);
    const operation = input.operation;
    const fieldsByOp = {
      storageToken: ["operation"],
      listUsers: ["operation", "cursor"],
      createUser: ["operation", "email", "password", "permissions"],
      deleteUser: ["operation", "userId"],
      revokeSessions: ["operation", "userId"],
      setUserActive: ["operation", "userId", "active"],
      setPermissions: ["operation", "userId", "permissions"],
      setPassword: ["operation", "userId", "password"],
    };
    const fields = fieldsByOp[operation];
    if (!fields) throw new BridgeFailure(400, "invalid_operation");
    if (Object.keys(input).some((k) => !fields.includes(k))) throw new BridgeFailure(400, "invalid_request");
    if (operation === "storageToken") return this.storageToken(actor, jwt);
    if (!labelsOf(actor).has(ADMIN)) throw new BridgeFailure(403, "forbidden");
    switch (operation) {
      case "listUsers":
        return this.listUsers(input);
      case "createUser":
        return this.createUser(input);
      default:
        return this.mutateUser(operation, actor, input);
    }
  }

  async storageToken(actor, jwt) {
    await this.verifyBucketPolicy();
    const userId = actor.$id;
    const effective = effectiveLabels(actor);
    if (effective.size === 0) throw new BridgeFailure(403, "forbidden");
    const shadow = await this.syncShadow(actor, true);
    if (!shadow) throw new BridgeFailure(503, "bridge_unavailable");
    // Verify again after provisioning, not just before potentially slow cross-project calls.
    const latest = await this.api.account(jwt);
    requireActiveActor(latest);
    if (latest.$id !== userId || !setsEqual(effectiveLabels(latest), effective)) {
      await this.denyShadow(userId);
      throw new BridgeFailure(403, "forbidden");
    }
    const token = await this.api.storage("POST", `/users/${shadow}/jwts`, { duration: TOKEN_SECONDS });
    return {
      projectId: STORAGE_PROJECT,
      bucketId: BUCKET,
      authUserId: userId,
      jwt: token.jwt,
      expiresIn: TOKEN_SECONDS,
    };
  }

  async verifyBucketPolicy() {
    const bucket = await this.api.storage("GET", `/storage/buckets/${BUCKET}`);
    const perms = new Set(Array.isArray(bucket.$permissions) ? bucket.$permissions : []);
    if (
      bucket.$id !== BUCKET ||
      bucket.enabled !== true ||
      bucket.fileSecurity !== false ||
      !setsEqual(perms, BUCKET_PERMISSIONS)
    ) {
      throw new BridgeFailure(503, "bucket_policy");
    }
  }

  async syncShadow(user, create) {
    const userId = user.$id;
    const id = shadowId(userId);
    let shadow = await this.shadowOrNull(userId);
    if (!shadow) {
      if (!create) return null;
      const password = crypto.randomBytes(48).toString("base64url");
      try {
        // Technical identity only: no copy of the login email, phone, or user's password.
        await this.api.storage("POST", "/users", { userId: id, password, name: "UniHub storage" });
        await this.api.storage("PUT", `/users/${id}/labels`, { labels: [MANAGED, sourceLabel(userId)] });
        shadow = await this.shadowOrNull(userId);
      } catch (error) {
        if (!(error instanceof UpstreamFailure) || error.status !== 409) throw error;
        shadow = await this.shadowOrNull(userId);
      }
    }
    if (!shadow) throw new BridgeFailure(503, "bridge_unavailable");
    const desired = user.status === true ? effectiveLabels(user) : new Set();
    const allLabels = new Set([MANAGED, sourceLabel(userId), ...desired]);
    await this.api.storage("PUT", `/users/${id}/labels`, { labels: [...allLabels] });
    await this.api.storage("PATCH", `/users/${id}/status`, { status: user.status === true && desired.size > 0 });
    return id;
  }

  /**
   * Refuse to take over an existing storage identity that is not a technical identity of this
   * bridge. A shadow that exists but is missing its identity labels is a half-provisioned
   * orphan (the label write that follows creation can be lost to a function timeout or a
   * dropped connection) or a leftover of an older label scheme: adopting and re-labelling it
   * in syncShadow() is what unlocks the account, while refusing it would lock the user out of
   * storage forever with a 409 "conflict" that no retry or list refresh can ever clear.
   */
  async shadowOrNull(userId) {
    const id = shadowId(userId);
    let user;
    try {
      user = await this.api.storage("GET", `/users/${id}`);
    } catch (error) {
      if (error instanceof UpstreamFailure && error.status === 404 && error.type === "user_not_found") return null;
      throw error;
    }
    if (user.$id !== id) {
      throw new BridgeFailure(409, "conflict");
    }
    if ((user.email && user.email.length > 0) || (user.phone && user.phone.length > 0)) {
      // A technical identity must never become an independently sign-in-capable account.
      await this.api.storage("PATCH", `/users/${id}/status`, { status: false });
      await this.api.storage("PUT", `/users/${id}/labels`, { labels: [MANAGED, sourceLabel(userId)] });
      throw new BridgeFailure(409, "conflict");
    }
    const source = sourceLabel(userId);
    // Already bound to a different source identity: never rebind, it may hold another user's files.
    for (const label of labelsOf(user)) {
      if (label.startsWith("src") && label !== source) throw new BridgeFailure(409, "conflict");
    }
    return user;
  }

  async denyShadow(userId) {
    if (!(await this.shadowOrNull(userId))) return;
    const id = shadowId(userId);
    await this.api.storage("PATCH", `/users/${id}/status`, { status: false });
    await this.api.storage("PUT", `/users/${id}/labels`, { labels: [MANAGED, sourceLabel(userId)] });
  }

  async listUsers(input) {
    const cursor = input.cursor || "";
    if (cursor && !validId(cursor)) throw new BridgeFailure(400, "invalid_request");
    const queries = [query("limit", null, [100]), query("orderAsc", "$id")];
    if (cursor) queries.push(query("cursorAfter", null, [cursor]));
    const page = (await this.api.auth("GET", "/users", undefined, queries)).users;
    const visible = page.filter((u) => validEmail(u.email || "")).map(publicProfile);
    const nextCursor = page.length === 100 ? page[99].$id : "";
    return { users: visible, cursor: nextCursor };
  }

  async createUser(input) {
    const email = String(input.email || "").trim().toLowerCase();
    if (!validEmail(email)) throw new BridgeFailure(400, "invalid_email");
    const password = requirePassword(input);
    const desired = permissionLabels(input.permissions);
    const id = crypto.randomUUID();
    await this.api.auth("POST", "/users", {
      userId: id,
      email,
      password,
      name: email.split("@")[0].slice(0, 128),
    });
    // If setting labels fails, the new account has NO storage permissions; there is no privileged default.
    const user = await this.api.auth("PUT", `/users/${id}/labels`, { labels: [...desired] });
    return { user: publicProfile(user) };
  }

  async mutateUser(operation, actor, input) {
    const id = input.userId;
    if (!validId(id)) throw new BridgeFailure(400, "invalid_request");
    const target = await this.api.auth("GET", `/users/${id}`);
    if (id === actor.$id || labelsOf(target).has(ADMIN)) throw new BridgeFailure(403, "protected_admin");
    // Validate before making any changes, then revoke storage access first (fail closed on partial failures).
    const desired = operation === "setPermissions" ? permissionLabels(input.permissions) : null;
    const newPassword = operation === "setPassword" ? requirePassword(input) : null;
    if (operation === "setUserActive" && typeof input.active !== "boolean") throw new BridgeFailure(400, "invalid_request");
    await this.denyShadow(id);
    switch (operation) {
      case "deleteUser":
        await this.api.auth("DELETE", `/users/${id}`);
        // Retain the disabled technical identity as a tombstone. No old JWT can regain access.
        return { ok: true };
      case "setUserActive":
        await this.api.auth("PATCH", `/users/${id}/status`, { status: input.active });
        break;
      case "setPermissions": {
        const preserved = new Set([...labelsOf(target)].filter((l) => ![READ, CREATE, MODIFY].includes(l)));
        await this.api.auth("PUT", `/users/${id}/labels`, { labels: [...preserved, ...(desired || [])] });
        break;
      }
      case "setPassword":
        await this.api.auth("PATCH", `/users/${id}/password`, { password: newPassword });
        await this.api.auth("DELETE", `/users/${id}/sessions`);
        break;
      case "revokeSessions":
        await this.api.auth("DELETE", `/users/${id}/sessions`);
        break;
      default:
        throw new BridgeFailure(400, "invalid_operation");
    }
    if (operation !== "revokeSessions" && operation !== "setPassword") {
      await this.syncShadow(await this.api.auth("GET", `/users/${id}`), false);
    }
    return { ok: true };
  }
}

export default async ({ req, res }) => {
  const headers = { "content-type": "application/json", "cache-control": "no-store", pragma: "no-cache" };
  const errorRes = (status, code) => res.text(JSON.stringify({ code }), status, headers);
  try {
    if (process.env.APPWRITE_FUNCTION_PROJECT_ID !== AUTH_PROJECT) return errorRes(503, "bridge_configuration");
    if (req.method !== "POST") return errorRes(405, "invalid_method");

    const bodyText = typeof req.bodyText === "string" ? req.bodyText : "";
    if (Buffer.byteLength(bodyText, "utf8") > 16 * 1024) return errorRes(413, "invalid_request");

    const jwt = String((req.headers && req.headers["x-appwrite-user-jwt"]) || "");
    if (!jwt) return errorRes(401, "unauthorized");

    // Appwrite supplies a dynamic key with the function's configured auth-project scopes.
    const authKey = String((req.headers && req.headers["x-appwrite-key"]) || "");
    const storageKey = String(process.env.STORAGE_API_KEY || "");
    if (
      !authKey ||
      !storageKey ||
      authKey.length > 8192 ||
      storageKey.length > 8192 ||
      hasControlChars(authKey) ||
      hasControlChars(storageKey)
    ) {
      return errorRes(503, "bridge_configuration");
    }

    let input;
    try {
      input = JSON.parse(bodyText || "{}");
    } catch {
      return errorRes(400, "invalid_request");
    }

    const api = makeApi(authKey, storageKey);
    const result = await new BridgeService(api).execute(jwt, input);
    return res.text(JSON.stringify(result), 200, headers);
  } catch (failure) {
    if (failure instanceof BridgeFailure) return errorRes(failure.status, failure.code);
    if (failure instanceof UpstreamFailure) {
      if (failure.status === 409) return errorRes(409, "conflict");
      if (failure.status === 404 && failure.type === "user_not_found") return errorRes(404, "not_found");
      return errorRes(502, "bridge_upstream");
    }
    // No exception message, body, password, key, or JWT is written to runtime logs.
    return errorRes(503, "bridge_unavailable");
  }
};
