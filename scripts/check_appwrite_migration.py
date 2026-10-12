#!/usr/bin/env python3
"""Source-level migration guard. This is NOT a substitute for Kotlin/Android compilation."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
checks = 0


def check(condition: bool, message: str) -> None:
    global checks
    if not condition:
        raise SystemExit(f"FAIL: {message}")
    checks += 1


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


APP = "app/src/main/java/com/unihub/app/"
config = read(APP + "data/appwrite/AppwriteConfig.kt")
bridge_config = read("service/appwrite/auth-bridge/BridgeService.kt")
for name, value in {
    "AUTH_PROJECT_ID": "6acd036f0022a495a9df",
    "STORAGE_PROJECT_ID": "6acd0a6800189ccc164a",
    "STORAGE_BUCKET_ID": "6acd11520015520ba016",
    "AUTH_ENDPOINT": "https://nyc.cloud.appwrite.io/v1",
    "STORAGE_ENDPOINT": "https://nyc.cloud.appwrite.io/v1",
}.items():
    check(f'const val {name} = "{value}"' in config, f"incorrect public config: {name}")
    check(value in bridge_config, f"server/client config drift: {name}")

for path in (
    "service/cloudflare",
    APP + "data/cloud/CloudflareR2Client.kt",
    APP + "data/cloud/CloudflareR2Config.kt",
    APP + "data/cloud/S3Protocol.kt",
    APP + "data/provision",
    APP + "feature/auth/CloudConnectionDialogs.kt",
):
    check(not (ROOT / path).exists(), f"obsolete source still exists: {path}")

for path in (ROOT / "app/src/main").rglob("*"):
    if path.suffix not in {".kt", ".xml"}:
        continue
    text = path.read_text(encoding="utf-8")
    check(not re.search(r"Cloudflare|CloudflareR2|R2Credentials|S3Protocol|S3Encoding|AWS4-HMAC|PROVISIONING_URL|data\.provision", text, re.I),
          f"old provider reference in {path.relative_to(ROOT)}")
    check(not re.search(r"STORAGE_API_KEY|X-Appwrite-Key|setSelfSigned\(true\)|setKey\(", text, re.I),
          f"server-only secret/unsafe client configuration in {path.relative_to(ROOT)}")

module = read(APP + "di/AppwriteModule.kt")
for kind in ("Auth", "Storage"):
    body = module.split(f"fun provide{kind}AppwriteClient", 1)[1].split("@Provides", 1)[0]
    check(f"AppwriteConfig.{kind.upper()}_PROJECT_ID" in body, f"wrong {kind} client project")
    check(f"AppwriteConfig.{kind.upper()}_ENDPOINT" in body, f"wrong {kind} client endpoint")
check('"appwrite_${projectId}_$name"' in module, "SDK cookie preferences are not project-scoped")
check("@AuthAppwrite private val account: Account" in read(APP + "data/auth/AppwriteAuthRepository.kt"), "Account is not auth-qualified")
check("@StorageAppwrite storageAppwriteClient: Client" in read(APP + "data/cloud/AppwriteStorageRepository.kt"), "file repository is not storage-qualified")
check("private val storageRepository: AppwriteStorageRepository" in read(APP + "data/cloud/CloudSyncManager.kt"), "sync still bypasses file repository")
check("STORAGE_PROJECT_ID" not in read(APP + "data/auth/AppwriteAuthRepository.kt"), "auth repository addresses storage project directly")
check("implementation(libs.appwrite)" in read("app/build.gradle.kts"), "missing Android SDK dependency")
check('project(":appwrite-bridge")' not in read("app/build.gradle.kts"), "server implementation must not be linked into APK")
check("service/cloudflare" not in read(".github/workflows/build.yml"), "CI references deleted service")

for file in ("backup_rules.xml", "data_extraction_rules.xml"):
    tree = ET.fromstring(read("app/src/main/res/xml/" + file))
    groups = [tree] if file == "backup_rules.xml" else list(tree)
    for group in groups:
        check(any(e.get("domain") == "sharedpref" and e.get("path") == "." for e in group.findall("exclude")),
              f"SDK sessions are transferable through {file}/{group.tag}")

check('api.account(jwt)' in bridge_config, "bridge must verify caller via Account")
check('bucket.opt("fileSecurity") != false' in bridge_config, "bucket policy must disable per-file ACL bypasses")
check('stringSet(bucket.optJSONArray("\\$permissions")) != BridgeConfig.bucketPermissions' in bridge_config,
      "bucket policy must reject public or unexpected permissions")
check("context.log(" not in read("service/appwrite/auth-bridge/Index.kt"), "bridge must not log secret-bearing executions")
check((ROOT / "تعليمات.md").is_file(), "repository instructions were removed")
print(f"PASS: {checks} static migration checks (not a build or live cloud test).")
