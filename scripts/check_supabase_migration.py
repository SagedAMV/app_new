#!/usr/bin/env python3
"""Source-level migration guard for the Appwrite → Supabase move.

This is NOT a substitute for Kotlin/Android compilation or for the live
cloud tests in `supabase/tests/`. It only asserts *static* facts that are
easy to break silently:

  * no Appwrite code, dependency or bridge is left in the repository;
  * no privileged Supabase key (sb_secret_…) exists in the app or in the
    project files that ship with the repository;
  * the Supabase layer is the one the sync manager actually consumes;
  * the protocol that the local catalog depends on did not change shape.
"""
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

# ── 1) لا Appwrite في المستودع ────────────────────────────────────────────
for path in (
    "service/appwrite",
    APP + "data/appwrite",
    APP + "data/auth/AppwriteAuthRepository.kt",
    APP + "di/AppwriteModule.kt",
    APP + "data/cloud/AppwriteStorageRepository.kt",
):
    check(not (ROOT / path).exists(), f"obsolete source still exists: {path}")

for path in (ROOT / "app/src/main").rglob("*"):
    if path.suffix not in {".kt", ".xml"}:
        continue
    text = path.read_text(encoding="utf-8")
    # مراجع Appwrite مسموحة داخل تعليقات تشرح تاريخ الترحيل فقط.
    code = "\n".join(
        line for line in text.splitlines()
        if not line.strip().startswith(("*", "//", "/*"))
    )
    check("io.appwrite" not in code and "AppwriteConfig" not in code and "AppwriteException" not in code,
          f"Appwrite API reference in {path.relative_to(ROOT)}")

build = read("app/build.gradle.kts")
catalog = read("gradle/libs.versions.toml")
check("libs.appwrite" not in build, "Appwrite SDK dependency still declared")
check("io.appwrite" not in catalog, "Appwrite SDK still in the version catalog")
check('include(":appwrite-bridge")' not in read("settings.gradle.kts"), "bridge module still linked")
check("service/appwrite" not in read(".github/workflows/build.yml"), "CI references deleted service")

# ── 2) لا مفتاح إداري داخل التطبيق أو ملفات المشروع ──────────────────────
# النمط = قيمة مفتاح فعلية (بادئة + 20 محرفًا على الأقل)، لا مجرد ذكر الاسم.
SECRET = re.compile(r"sb_secret_[A-Za-z0-9_\-]{20,}")
for path in list((ROOT / "app/src").rglob("*")) + list((ROOT / "supabase").rglob("*")) + list((ROOT / "docs").rglob("*")):
    if not path.is_file() or path.suffix not in {".kt", ".kts", ".toml", ".xml", ".sh", ".md"}:
        continue
    text = path.read_text(encoding="utf-8", errors="ignore")
    check(SECRET.search(text) is None, f"privileged Supabase key in {path.relative_to(ROOT)}")
check(SECRET.search(read("مراجعة.md")) is None, "privileged Supabase key in the review document")
check(SECRET.search(read("تفكير.md")) is None, "privileged Supabase key in the operating protocol")

# ── 3) طبقة Supabase هي ما تستهلكه المزامنة ──────────────────────────────
config = read(APP + "data/supabase/SupabaseConfig.kt")
check('const val PROJECT_URL = "https://' in config, "Supabase project URL missing")
check("sb_publishable_" in config, "publishable key missing from the app config")
check(SECRET.search(config) is None, "privileged key in the app config")
check('const val STORAGE_BUCKET_ID = "unihub-files"' in config, "unexpected storage bucket")

sync = read(APP + "data/cloud/CloudSyncManager.kt")
check("private val storageRepository: SupabaseStorageRepository" in sync,
      "sync manager does not consume the Supabase transport")
check("AppwriteStorageRepository" not in sync, "sync manager still references the Appwrite repository")

transport = read(APP + "data/supabase/SupabaseStorageTransport.kt")
for member in ("listBucketObjects", "uploadFile", "uploadText", "downloadTextObject",
               "downloadFile", "deleteObject", "sha256Hex"):
    check(f"fun {member}(" in transport, f"transport lost a member of the contract: {member}")

# ── 4) البروتوكول لم يتغيّر شكله (CatalogStore يعتمد عليه) ───────────────
protocol = read(APP + "data/cloud/CloudFileProtocol.kt")
check('"f" + sha256(key).take(35)' in protocol, "file id formula changed — local links would break")
check("m${generation.toString().padStart(16, '0')}_" in protocol, "manifest id formula changed")
check("fun latestManifestId(" in protocol, "manifest conflict detection lost")

# ── 5) ذاكرة الجلسة محميّة من النسخ الاحتياطي ────────────────────────────
for file in ("backup_rules.xml", "data_extraction_rules.xml"):
    tree = ET.fromstring(read("app/src/main/res/xml/" + file))
    groups = [tree] if file == "backup_rules.xml" else list(tree)
    for group in groups:
        check(any(e.get("domain") == "sharedpref" and e.get("path") == "." for e in group.findall("exclude")),
              f"session store is transferable through {file}/{group.tag}")

# ── 6) تعليمات المستودع لم تُحذف ─────────────────────────────────────────
check((ROOT / "تعليمات.md").is_file(), "repository instructions were removed")
check((ROOT / "تفكير.md").is_file(), "operating protocol file was removed")
check((ROOT / "طبيعة تطبيق.md").is_file(), "code-organization rules file was removed")

print(f"PASS: {checks} static migration checks (not a build or live cloud test).")
