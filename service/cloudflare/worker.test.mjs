import test from "node:test";
import assert from "node:assert/strict";
import { webcrypto, createHash, createHmac, createDecipheriv } from "node:crypto";
import worker from "./worker.mjs";

if (!globalThis.crypto) Object.defineProperty(globalThis, "crypto", { value: webcrypto });
const b64 = bytes => Buffer.from(bytes).toString("base64url");
const unb64 = text => Buffer.from(text, "base64url");
const hash = text => createHash("sha256").update(text).digest("hex");

// Deliberately synthetic secrets. These tests never call Cloudflare, R2, or the network.
class FakeD1 {
  constructor() { this.rows = new Map(); this.statements = []; }
  prepare(sql) {
    return { bind: (...args) => ({
      first: async () => {
        this.statements.push({ sql, args });
        assert.match(sql, /UPDATE provisioning_invites/);
        const [claim, claimExpiry, token, now] = args;
        const row = this.rows.get(token);
        if (!row || !row.enabled || claimExpiry <= now) return null;
        if (row.claim_hash == null && row.expires_at > now) {
          row.claim_hash = claim; row.claim_expires_at = claimExpiry;
        } else if (row.claim_hash !== claim || row.claim_expires_at <= now) return null;
        return { token_hash: token };
      },
      run: async () => {
        assert.match(sql, /DELETE FROM provisioning_invites/);
        for (const [key, row] of this.rows) {
          if ((row.claim_hash == null ? row.expires_at : row.claim_expires_at) < args[0]) this.rows.delete(key);
        }
        return { success: true };
      }
    }) };
  }
}

async function fixture() {
  const invitation = b64(webcrypto.getRandomValues(new Uint8Array(32)));
  const db = new FakeD1();
  db.rows.set(hash(invitation), { enabled: 1, expires_at: Date.now() + 3_600_000, claim_hash: null, claim_expires_at: null });
  const env = { PROVISIONING_DB: db, RATE_LIMITER: { limit: async () => ({ success: true }) },
    R2_ACCOUNT_ID: "0".repeat(32), R2_BUCKET_NAME: "test-bucket",
    R2_ACCESS_KEY_ID: "TEST_ACCESS_NOT_A_SECRET", R2_SECRET_ACCESS_KEY: "TEST_SECRET_NOT_A_REAL_CLOUD_KEY" };
  return { invitation, db, env };
}

async function input(invitation, env) {
  const receiver = await webcrypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-384" }, false, ["deriveBits"]);
  const publicKey = b64(await webcrypto.subtle.exportKey("spki", receiver.publicKey));
  return { receiver, body: { invitation, request: { type: "unihub-receive", version: 1,
    requestId: b64(webcrypto.getRandomValues(new Uint8Array(32))), publicKey,
    expiresAt: Date.now() + 900_000, accountId: env.R2_ACCOUNT_ID, bucketName: env.R2_BUCKET_NAME } } };
}

function request(body, url = "https://connect.example.invalid/v1/connection") {
  return new Request(url, { method: "POST", headers: { "Content-Type": "application/json", "CF-Connecting-IP": "192.0.2.1" }, body: JSON.stringify(body) });
}

async function decrypt(envelope, receiver) {
  const publicKey = await webcrypto.subtle.importKey("spki", unb64(envelope.senderPublicKey),
    { name: "ECDH", namedCurve: "P-384" }, false, []);
  const shared = Buffer.from(await webcrypto.subtle.deriveBits({ name: "ECDH", public: publicKey }, receiver.privateKey, 384));
  const info = Buffer.from(`unihub-r2-envelope-v1|${envelope.requestId}|${envelope.expiresAt}|${envelope.receiverKeyHash}|${envelope.senderPublicKey}`);
  // Independent HKDF extract/expand and Node AES-GCM verify the WebCrypto implementation.
  const prk = createHmac("sha384", unb64(envelope.requestId)).update(shared).digest();
  const key = createHmac("sha384", prk).update(info).update(Buffer.from([1])).digest().subarray(0, 32);
  const data = unb64(envelope.ciphertext);
  const cipher = createDecipheriv("aes-256-gcm", key, unb64(envelope.iv));
  cipher.setAAD(info); cipher.setAuthTag(data.subarray(-16));
  try { return JSON.parse(Buffer.concat([cipher.update(data.subarray(0, -16)), cipher.final()]).toString("utf8")); }
  finally { shared.fill(0); prk.fill(0); key.fill(0); }
}

test("only the intended receiver gets the original permanent keys; DB/response are not plaintext", async () => {
  const { invitation, env, db } = await fixture();
  const { receiver, body } = await input(invitation, env);
  const res = await worker.fetch(request(body), env);
  assert.equal(res.status, 200);
  assert.equal(res.headers.get("Cache-Control"), "no-store");
  const text = await res.text();
  assert.ok(!text.includes(env.R2_ACCESS_KEY_ID)); assert.ok(!text.includes(env.R2_SECRET_ACCESS_KEY));
  const envelope = JSON.parse(text);
  const plain = await decrypt(envelope, receiver);
  assert.deepEqual(plain, { type: "r2-permanent", accountId: env.R2_ACCOUNT_ID, bucketName: env.R2_BUCKET_NAME,
    accessKeyId: env.R2_ACCESS_KEY_ID, secretAccessKey: env.R2_SECRET_ACCESS_KEY });
  assert.equal(plain.sessionToken, undefined); assert.equal(plain.expiresAt, undefined);
  const statements = JSON.stringify(db.statements);
  assert.ok(!statements.includes(invitation)); assert.ok(!statements.includes(env.R2_SECRET_ACCESS_KEY));
  const other = await input(invitation, env);
  await assert.rejects(decrypt(envelope, other.receiver));
  const changed = unb64(envelope.ciphertext); changed[0] ^= 1;
  await assert.rejects(decrypt({ ...envelope, ciphertext: b64(changed) }, receiver));
});

test("an exact retry succeeds but a different device/request cannot replay the invitation", async () => {
  const { invitation, env } = await fixture();
  const first = await input(invitation, env);
  const other = await input(invitation, env);
  assert.equal((await worker.fetch(request(first.body), env)).status, 200);
  assert.equal((await worker.fetch(request(first.body), env)).status, 200);
  assert.equal((await worker.fetch(request(other.body), env)).status, 403);
});

test("concurrent claims have only one winner in the atomic-write model", async () => {
  const { invitation, env } = await fixture();
  const a = await input(invitation, env); const b = await input(invitation, env);
  const res = await Promise.all([worker.fetch(request(a.body), env), worker.fetch(request(b.body), env)]);
  assert.deepEqual(res.map(r => r.status).sort(), [200, 403]);
});

test("expired/disabled invitation and expired request cannot deliver keys", async () => {
  const { invitation, env, db } = await fixture();
  const { body } = await input(invitation, env);
  db.rows.get(hash(invitation)).expires_at = Date.now() - 1;
  assert.equal((await worker.fetch(request(body), env)).status, 403);
  db.rows.get(hash(invitation)).expires_at = Date.now() + 60_000;
  db.rows.get(hash(invitation)).enabled = 0;
  assert.equal((await worker.fetch(request(body), env)).status, 403);
  body.request.expiresAt = Date.now() - 1;
  assert.equal((await worker.fetch(request(body), env)).status, 400);
});

test("URL, rate limit, body size, tenant, curve and schema are enforced", async () => {
  const { invitation, env, db } = await fixture();
  const { body } = await input(invitation, env);
  assert.equal((await worker.fetch(request(body, "http://connect.example.invalid/v1/connection"), env)).status, 404);
  assert.equal((await worker.fetch(request(body, "https://connect.example.invalid/v1/connection?token=secret"), env)).status, 404);
  assert.equal((await worker.fetch(new Request("https://connect.example.invalid/v1/connection"), env)).status, 405);
  assert.equal((await worker.fetch(request(body), { ...env, RATE_LIMITER: { limit: async () => ({ success: false }) } })).status, 429);
  assert.equal((await worker.fetch(request({ ...body, padding: "x".repeat(4096) }), env)).status, 400);
  for (const patch of [{ version: 9 }, { accountId: "1".repeat(32) }, { expiresAt: Date.now() + 1_800_000 }, { publicKey: "bogus" }]) {
    assert.equal((await worker.fetch(request({ ...body, request: { ...body.request, ...patch } }), env)).status, 400);
  }
  const wrong = await webcrypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, false, ["deriveBits"]);
  const wrongPublic = b64(await webcrypto.subtle.exportKey("spki", wrong.publicKey));
  assert.equal((await worker.fetch(request({ ...body, request: { ...body.request, publicKey: wrongPublic } }), env)).status, 400);
  assert.equal(db.statements.length, 0);
});

test("missing secrets/bindings fail closed and expired claim cleanup is supported", async () => {
  const { invitation, env, db } = await fixture();
  const { body } = await input(invitation, env);
  assert.equal((await worker.fetch(request(body), { ...env, PROVISIONING_DB: undefined })).status, 503);
  assert.equal((await worker.fetch(request(body), { ...env, R2_SECRET_ACCESS_KEY: "" })).status, 503);
  db.rows.get(hash(invitation)).expires_at = Date.now() - 1;
  let pending;
  await worker.scheduled({}, env, { waitUntil: p => { pending = p; } });
  await pending;
  assert.equal(db.rows.size, 0);
});
