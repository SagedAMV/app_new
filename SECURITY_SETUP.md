# Security setup for UniHub

## Cloudflare R2 credentials

R2 Access Key ID and Secret Access Key are no longer compiled into the application. Revoke the previously exposed token in Cloudflare and create a **newly rotated, bucket-scoped** credential pair. On first launch, enter it from “إعداد اتصال السحابة” on the login screen; if an existing token was revoked or mistyped, use “إصلاح أو تغيير اتصال السحابة” before signing in. Once authenticated, settings can also be changed under Backup → Cloudflare R2. The app stores these values encrypted with a non-exportable key in Android Keystore. If Android Keystore is reset or credentials cannot be decrypted, enter the keys again.

Use a dedicated R2 token with the minimum permissions required for this app and bucket; do not reuse an account-wide credential. A mobile app is not a trusted place for a long-lived bucket-wide secret. The durable production architecture is a small authenticated backend that issues narrowly scoped, short-lived access (or presigned URLs), rather than sharing R2 master credentials with every client.

## Initial admin account

A fresh, empty auth registry can be bootstrapped by entering username `saged` and choosing a password of at least 12 characters. The first password is not built into the app. New and changed user passwords must be at least 10 characters. Passwords are stored as salted PBKDF2-HMAC-SHA256 verifiers; the administrator can reset a password but cannot read it. Older password hashes are upgraded when that user next signs in. **Important for existing installations:** the previous app shipped with a publicly exposed default admin password. If the old default password was never changed on your existing cloud registry, sign in only after rotating the R2 token, then immediately change the admin password and any user passwords that were reused elsewhere. This build does not overwrite a present registry or silently recreate the admin if it is missing.

## Release signing (local only)

No signing keystore is included in the source archive. To sign a release locally, create `keystore.properties` at the project root with these fields (do not commit it):

```properties
storeFile=path/to/your-release-key.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_KEY_PASSWORD
```

`storeFile` is resolved relative to the project root. Without this file, debug builds remain buildable and release artifacts are unsigned. Use a key you control; do not reuse the signing material distributed in the previous archive.

## Important rotation checklist

1. Revoke the previously embedded R2 key at Cloudflare and create a new, bucket-scoped token. Deleting a secret from this source tree does not revoke a credential already exposed.
2. If the old Android signing key was used for a distributed build, treat it as compromised. For an app not yet published, generate a new key. For an existing Play distribution, follow the platform's supported signing-key upgrade process rather than blindly replacing it.
3. Update all devices to this build before relying on the new password-hash format; older builds do not understand PBKDF2 verifier strings.

## Audit scope and known limitations

- The shipped R2 token and release keystore were removed from this source archive. This does **not** revoke credentials already exposed in earlier builds: rotate/revoke them separately.
- Release builds require a locally supplied `keystore.properties`. Without it, do not expect a distributable, correctly signed release APK. Since the previous signing keystore was exposed, an existing Play app must use Play App Signing's supported key-upgrade process; a freshly generated key may not update older sideloaded installs.
- This archive contains historical `verification/` reports from earlier source revisions. They are records only and are not evidence that this exact revision has been built or run.
- The app still talks to R2 directly from the client and its authorization registry is a client-managed JSON object. Keystore encryption protects stored credentials at rest, but cannot make a long-lived bucket secret safe from a rooted/compromised client or enforce robust server-side authorization. Production-grade isolation requires an authenticated backend/Cloudflare Worker and narrowly scoped, short-lived credentials.
- Room no longer has `fallbackToDestructiveMigration`. Its schema is currently version 1 with no explicit migrations. If the local DB cannot be opened, `DatabaseSelfHeal` first attempts to preserve the database and SQLite sidecars under the app-private `filesDir/database-recovery/`, then rebuilds the active DB. There is not yet an in-app way to browse/export/restore those recovery snapshots; implement explicit Room migrations plus a user-facing recovery flow before production.
- Full Gradle/Android build and emulator/device testing were unavailable in the audit environment because Gradle 8.9 could not be retrieved and no Android SDK was installed. The report in `AUDIT_REPORT_AR.md` distinguishes source-level checks from tests that remain to be run locally.
