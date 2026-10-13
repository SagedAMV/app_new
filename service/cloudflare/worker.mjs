// Cloud-only API; the Android app remains Kotlin. No web UI, HTML, credentials or API tokens in source.
const encoder = new TextEncoder();
const MAX_BODY = 4096;
const LIFETIME = 15 * 60 * 1000;

function response(status, body) {
  return new Response(JSON.stringify(body), { status, headers: {
    "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store",
    "Pragma": "no-cache", "X-Content-Type-Options": "nosniff",
    "Content-Security-Policy": "default-src 'none'",
  } });
}

function encode(bytes) {
  return btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}
function decode(value, max = 4096) {
  if (typeof value !== "string" || value.length > max || !/^[A-Za-z0-9_-]+$/.test(value)) throw new Error("input");
  const plain = atob(value.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(value.length / 4) * 4, "="));
  const bytes = Uint8Array.from(plain, c => c.charCodeAt(0));
  if (encode(bytes) !== value) throw new Error("input");
  return bytes;
}
async function digest(bytes) { return new Uint8Array(await crypto.subtle.digest("SHA-256", bytes)); }
function hex(bytes) { return Array.from(bytes, b => b.toString(16).padStart(2, "0")).join(""); }
function bucket(value) { return typeof value === "string" && /^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$/.test(value) && !value.includes(".."); }
function credential(value, max) { return typeof value === "string" && value.length > 0 && value.length <= max && !/[\u0000-\u001f\u007f-\u009f]/.test(value) && value.trim() === value; }

async function readBody(request) {
  if (!request.headers.get("Content-Type")?.toLowerCase().startsWith("application/json")) throw new Error("input");
  const declared = Number(request.headers.get("Content-Length") ?? "0");
  if (!Number.isFinite(declared) || declared > MAX_BODY || !request.body) throw new Error("input");
  const reader = request.body.getReader();
  const chunks = [];
  let size = 0;
  let timedOut = false;
  const timer = setTimeout(() => { timedOut = true; reader.cancel().catch(() => {}); }, 5000);
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.length;
      if (size > MAX_BODY) throw new Error("input");
      chunks.push(value);
    }
  } finally { clearTimeout(timer); await reader.cancel().catch(() => {}); }
  if (timedOut) throw new Error("input");
  const bytes = new Uint8Array(size);
  let at = 0;
  for (const chunk of chunks) { bytes.set(chunk, at); at += chunk.length; }
  return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
}

function cloudCredentials(env) {
  if (!/^[0-9a-f]{32}$/.test(env.R2_ACCOUNT_ID ?? "") || !bucket(env.R2_BUCKET_NAME) ||
      !credential(env.R2_ACCESS_KEY_ID, 256) || !credential(env.R2_SECRET_ACCESS_KEY, 512)) throw new Error("configuration");
  return { type: "r2-permanent", accountId: env.R2_ACCOUNT_ID, bucketName: env.R2_BUCKET_NAME,
    accessKeyId: env.R2_ACCESS_KEY_ID, secretAccessKey: env.R2_SECRET_ACCESS_KEY };
}

async function validate(input, credentials, now) {
  const req = input?.request;
  if (typeof input?.invitation !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(input.invitation) ||
      !req || req.type !== "unihub-receive" || req.version !== 1 ||
      !Number.isSafeInteger(req.expiresAt) || req.expiresAt <= now || req.expiresAt - now > LIFETIME ||
      req.accountId !== credentials.accountId || req.bucketName !== credentials.bucketName ||
      decode(req.requestId, 43).length !== 32) throw new Error("input");
  const publicBytes = decode(req.publicKey, 220);
  if (publicBytes.length < 100 || publicBytes.length > 160) throw new Error("input");
  const publicKey = await crypto.subtle.importKey("spki", publicBytes, { name: "ECDH", namedCurve: "P-384" }, false, []);
  const receiverHash = encode(await digest(publicBytes));
  const claimHash = hex(await digest(encoder.encode(`${req.requestId}|${req.expiresAt}|${receiverHash}|${req.accountId}|${req.bucketName}`)));
  const tokenHash = hex(await digest(encoder.encode(input.invitation)));
  return { req, publicKey, receiverHash, claimHash, tokenHash };
}

async function seal(request, receiver, receiverHash, credentials) {
  const ephemeral = await crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-384" }, false, ["deriveBits"]);
  const senderPublicKey = encode(await crypto.subtle.exportKey("spki", ephemeral.publicKey));
  const info = encoder.encode(`unihub-r2-envelope-v1|${request.requestId}|${request.expiresAt}|${receiverHash}|${senderPublicKey}`);
  const shared = new Uint8Array(await crypto.subtle.deriveBits({ name: "ECDH", public: receiver }, ephemeral.privateKey, 384));
  let key;
  try {
    const hkdf = await crypto.subtle.importKey("raw", shared, "HKDF", false, ["deriveKey"]);
    key = await crypto.subtle.deriveKey({ name: "HKDF", hash: "SHA-384", salt: decode(request.requestId), info },
      hkdf, { name: "AES-GCM", length: 256 }, false, ["encrypt"]);
  } finally { shared.fill(0); }
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const plain = encoder.encode(JSON.stringify(credentials));
  let ciphertext;
  try { ciphertext = await crypto.subtle.encrypt({ name: "AES-GCM", iv, additionalData: info, tagLength: 128 }, key, plain); }
  finally { plain.fill(0); }
  return { type: "unihub-sealed", version: 1, requestId: request.requestId, receiverKeyHash: receiverHash,
    senderPublicKey, expiresAt: request.expiresAt, iv: encode(iv), ciphertext: encode(ciphertext) };
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.protocol !== "https:" || url.pathname !== "/v1/connection" || url.search) return response(404, { error: "not_found" });
    if (request.method !== "POST") return response(405, { error: "method" });
    if (!env.PROVISIONING_DB || !env.RATE_LIMITER) return response(503, { error: "unavailable" });
    try {
      // Cloudflare overwrites this header at its ingress. No Origin/CORS check is treated as authorization.
      const ip = request.headers.get("CF-Connecting-IP") ?? "unknown";
      const rate = await env.RATE_LIMITER.limit({ key: `connection:${ip}` });
      if (!rate.success) return response(429, { error: "rate_limit" });
      const credentials = cloudCredentials(env);
      let validated;
      try { validated = await validate(await readBody(request), credentials, Date.now()); }
      catch { return response(400, { error: "invalid_request" }); }
      const { req, publicKey, receiverHash, claimHash, tokenHash } = validated;
      // One atomic write: new claims bind the token to one device/request. Exact retries are safe
      // across isolates and restarts; a different recipient cannot claim an already used token.
      const row = await env.PROVISIONING_DB.prepare(`
        UPDATE provisioning_invites
        SET claim_hash = COALESCE(claim_hash, ?1), claim_expires_at = COALESCE(claim_expires_at, ?2)
        WHERE token_hash = ?3 AND enabled = 1 AND ?2 > ?4
          AND ((claim_hash IS NULL AND expires_at > ?4)
            OR (claim_hash = ?1 AND claim_expires_at > ?4))
        RETURNING token_hash
      `).bind(claimHash, req.expiresAt, tokenHash, Date.now()).first();
      if (!row) return response(403, { error: "invitation_rejected" });
      return response(200, await seal(req, publicKey, receiverHash, credentials));
    } catch {
      // Never log request bodies, invitation tokens, R2 keys, ciphertext, or environment values.
      return response(503, { error: "unavailable" });
    }
  },
  async scheduled(_controller, env, ctx) {
    ctx.waitUntil(env.PROVISIONING_DB.prepare(`DELETE FROM provisioning_invites
      WHERE (claim_hash IS NULL AND expires_at < ?1) OR (claim_hash IS NOT NULL AND claim_expires_at < ?1)`)
      .bind(Date.now()).run());
  },
};
