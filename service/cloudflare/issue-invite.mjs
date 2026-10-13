#!/usr/bin/env node
// Local operator tool only. Never sends tokens to a server or prints them in terminal logs.
import { randomBytes, createHash } from "node:crypto";
import { mkdirSync, lstatSync, chmodSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { join } from "node:path";

const help = "Usage: node service/cloudflare/issue-invite.mjs --label PHONE_LABEL --minutes 60\nCreates sensitive local JSON + hashed-invitation SQL in service/cloudflare/private/. No deployment is performed.";
const args = process.argv.slice(2);
if (args.includes("--help")) { console.log(help); process.exit(0); }

try {
  const options = new Map();
  if (args.length % 2) throw new Error("arguments");
  for (let i = 0; i < args.length; i += 2) {
    if (!["--label", "--minutes"].includes(args[i]) || options.has(args[i])) throw new Error("arguments");
    options.set(args[i], args[i + 1]);
  }
  const label = options.get("--label") ?? "device";
  const minutesText = options.get("--minutes") ?? "60";
  if (label.length < 1 || label.length > 64 || /[\u0000-\u001f\u007f-\u009f]/.test(label) || !/^\d{1,5}$/.test(minutesText)) throw new Error("arguments");
  const minutes = Number(minutesText);
  if (!Number.isSafeInteger(minutes) || minutes < 1 || minutes > 10080) throw new Error("arguments");
  const directory = fileURLToPath(new URL("./private/", import.meta.url));
  mkdirSync(directory, { recursive: true, mode: 0o700 });
  if (lstatSync(directory).isSymbolicLink()) throw new Error("directory");
  chmodSync(directory, 0o700);
  const token = randomBytes(32).toString("base64url");
  const tokenHash = createHash("sha256").update(token).digest("hex");
  const createdAt = Date.now();
  const expiresAt = createdAt + minutes * 60_000;
  const base = `invite-${createdAt}-${randomBytes(6).toString("hex")}`;
  const jsonPath = join(directory, `${base}.json`);
  const sqlPath = join(directory, `${base}.sql`);
  const sql = `INSERT INTO provisioning_invites (token_hash, label, expires_at, enabled)\nVALUES ('${tokenHash}', '${label.replaceAll("'", "''")}', ${expiresAt}, 1);\n`;
  writeFileSync(jsonPath, JSON.stringify({ label, invitation: token, invitationQr: `unihub-invite:v1:${token}`, createdAt, expiresAt }, null, 2) + "\n", { flag: "wx", mode: 0o600 });
  writeFileSync(sqlPath, sql, { flag: "wx", mode: 0o600 });
  console.log(`Sensitive invitation file: ${jsonPath}\nHashed database statement: ${sqlPath}\nApply only the SQL after explicit deployment approval. Send the invitation privately; never commit either file.`);
} catch {
  console.error(`Invitation creation failed. Check arguments/private-directory permissions.\n${help}`);
  process.exitCode = 1;
}
