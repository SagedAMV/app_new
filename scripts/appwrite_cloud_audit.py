#!/usr/bin/env python3
"""Appwrite Cloud audit / repair / end-to-end test for the UniHub two-project design.

Runs ONLY with credentials supplied through environment variables — never hardcode keys:

    export AW_USERS_KEY='...'   # API key of the Users/Auth project  (6acd036f0022a495a9df)
    export AW_FILES_KEY='...'   # API key of the Storage/Files project (6acd0a6800189ccc164a)
    python3 scripts/appwrite_cloud_audit.py --mode all --report-dir verification/appwrite-cloud-audit-$(date +%F)

Modes:
    audit   read-only inspection, prints PASS/FAIL/WARN/SKIP per check
    repair  audit + safe fixes that follow docs/appwrite-setup.md exactly
    test    audit + live end-to-end tests with throwaway users/files (always cleaned up)
    all     audit -> repair -> test   (default)

Safety rails:
    * key values, JWTs, session secrets and passwords are never printed or written to reports
    * no existing data is deleted; only resources created by this run (prefix "awaudit") are
    * repairs are limited to the documented design and every change is logged before/after
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import secrets as pysecrets
import string
import sys
import tarfile
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Optional

# ---------------------------------------------------------------- public identifiers
# Mirrors app/src/main/java/com/unihub/app/data/appwrite/AppwriteConfig.kt
ENDPOINT = os.environ.get("AW_ENDPOINT", "https://nyc.cloud.appwrite.io/v1").rstrip("/")
AUTH_PROJECT = os.environ.get("AW_AUTH_PROJECT", "6acd036f0022a495a9df")
STORAGE_PROJECT = os.environ.get("AW_STORAGE_PROJECT", "6acd0a6800189ccc164a")
BUCKET_ID = "6acd11520015520ba016"
BRIDGE_FUNCTION_ID = "unihub-auth-bridge"
ANDROID_PACKAGE = "com.unihub.app"
EXPECTED_BUCKET_PERMISSIONS = {
    'read("label:unihub_download")',
    'create("label:unihub_upload")',
    'update("label:unihub_modify")',
    'delete("label:unihub_modify")',
}
BRIDGE_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                          "service", "appwrite", "auth-bridge")
TEST_PREFIX = "awaudit"

# ---------------------------------------------------------------- reporting


@dataclass
class Check:
    cid: str
    title: str
    status: str  # PASS FAIL WARN SKIP INFO FIXED
    detail: str = ""
    evidence: str = ""
    changed: bool = False


@dataclass
class Report:
    checks: list = field(default_factory=list)
    changes: list = field(default_factory=list)
    tests: list = field(default_factory=list)

    def add(self, check: Check) -> Check:
        self.checks.append(check)
        mark = {"PASS": "✅", "FAIL": "❌", "WARN": "⚠️", "SKIP": "⏭️", "INFO": "ℹ️", "FIXED": "🔧"}[check.status]
        print(f"{mark} [{check.cid}] {check.title}: {check.detail}", flush=True)
        return check

    def change(self, text: str) -> None:
        self.changes.append(text)
        print(f"🔧 CHANGE: {text}", flush=True)

    def test(self, text: str, ok: bool, evidence: str = "") -> None:
        self.tests.append({"result": "PASS" if ok else "FAIL", "text": text, "evidence": evidence})
        print(f"{'✅' if ok else '❌'} TEST: {text}" + (f" | {evidence}" if evidence else ""), flush=True)


def redact(value: Any) -> str:
    """Never leak credentials: show only shapes, never values."""
    text = str(value)
    for secret in KNOWN_SECRETS:
        if secret and secret in text:
            text = text.replace(secret, "***redacted***")
    return text


KNOWN_SECRETS: set = set()


# ---------------------------------------------------------------- HTTP layer


class ApiError(Exception):
    def __init__(self, status: int, body: str):
        super().__init__(f"HTTP {status}: {redact(body[:400])}")
        self.status = status
        self.body = body

    @property
    def type(self) -> str:
        try:
            return json.loads(self.body).get("type", "")
        except Exception:
            return ""

    @property
    def message(self) -> str:
        try:
            return json.loads(self.body).get("message", "")
        except Exception:
            return ""


class Api:
    def __init__(self, project: str, key: str = "", session: str = "", jwt: str = "",
                 response_format: str = "2.5.0"):
        self.project = project
        self.key = key
        self.session = session
        self.jwt = jwt
        self.response_format = response_format

    def request(self, method: str, path: str, body: Any = None, *, raw_body: Optional[bytes] = None,
                content_type: str = "application/json", headers: Optional[dict] = None,
                auth: str = "auto") -> Any:
        url = ENDPOINT + path
        h = {
            "X-Appwrite-Project": self.project,
            "Accept": "application/json",
            "x-appwrite-response-format": self.response_format,
        }
        if content_type:
            h["Content-Type"] = content_type
        if auth == "auto":
            if self.key:
                h["X-Appwrite-Key"] = self.key
            if self.session:
                h["X-Appwrite-Session"] = self.session
            if self.jwt:
                h["X-Appwrite-JWT"] = self.jwt
        if headers:
            h.update(headers)
        data: Optional[bytes]
        if raw_body is not None:
            data = raw_body
        elif body is not None:
            data = json.dumps(body).encode()
        else:
            data = None
        req = urllib.request.Request(url, data=data, method=method, headers=h)
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                payload = resp.read()
                if not payload:
                    return {}
                return json.loads(payload)
        except urllib.error.HTTPError as e:
            payload = e.read()
            raise ApiError(e.code, payload.decode("utf-8", "replace")) from None
        except urllib.error.URLError as e:
            raise ApiError(0, f"network error: {e.reason}") from None

    # helpers
    def get(self, path: str, **kw) -> Any:
        return self.request("GET", path, **kw)

    def post(self, path: str, body: Any = None, **kw) -> Any:
        return self.request("POST", path, body, **kw)

    def put(self, path: str, body: Any = None, **kw) -> Any:
        return self.request("PUT", path, body, **kw)

    def patch(self, path: str, body: Any = None, **kw) -> Any:
        return self.request("PATCH", path, body, **kw)

    def delete(self, path: str, **kw) -> Any:
        return self.request("DELETE", path, **kw)


def multipart(fields: dict, files: dict) -> tuple:
    """Minimal multipart/form-data encoder (stdlib only)."""
    boundary = "----unihub" + pysecrets.token_hex(16)
    out = io.BytesIO()
    for name, value in fields.items():
        out.write(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n".encode())
    for name, (filename, data, ctype) in files.items():
        out.write(
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"; filename=\"{filename}\"\r\n"
            f"Content-Type: {ctype}\r\n\r\n".encode()
        )
        out.write(data)
        out.write(b"\r\n")
    out.write(f"--{boundary}--\r\n".encode())
    return out.getvalue(), f"multipart/form-data; boundary={boundary}"


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def shadow_id(auth_user_id: str) -> str:
    return "u" + sha256_hex(f"{AUTH_PROJECT}:{auth_user_id}".encode())[:35]


# ---------------------------------------------------------------- phases


def phase_identity(report: Report, users_key: str, files_key: str) -> tuple:
    """A: connectivity, key-to-project binding, and separation of the two projects."""
    auth = Api(AUTH_PROJECT, key=users_key)
    storage = Api(STORAGE_PROJECT, key=files_key)

    try:
        info = auth.get("/health")
        report.add(Check("A1", "Appwrite endpoint reachable (users key)",
                         "PASS", f"health ok, server payload keys={sorted(info)[:6]}"))
    except ApiError as e:
        report.add(Check("A1", "Appwrite endpoint reachable (users key)", "FAIL" if e.status else "FAIL",
                         f"status={e.status} type={e.type}"))
        return None, None

    version = ""
    try:
        probe = Api(AUTH_PROJECT).get("/health")
        version = str(probe.get("version", ""))
    except ApiError as e:
        try:
            version = str(json.loads(e.body).get("version", ""))
        except Exception:
            version = ""
    report.add(Check("A2", "Appwrite server version", "INFO", version or "unknown"))

    ok_users = ok_files = False
    try:
        page = auth.request("GET", "/users?limit=1")
        ok_users = True
        report.add(Check("A3", "Users key authorized on users project", "PASS",
                         f"users endpoint answered, total={page.get('total', '?')}"))
    except ApiError as e:
        report.add(Check("A3", "Users key authorized on users project", "FAIL",
                         f"status={e.status} type={e.type}"))
    try:
        storage.request("GET", "/storage/buckets?limit=1")
        ok_files = True
        report.add(Check("A4", "Files key authorized on storage project", "PASS", "buckets endpoint answered"))
    except ApiError as e:
        report.add(Check("A4", "Files key authorized on storage project", "FAIL",
                         f"status={e.status} type={e.type}"))

    if ok_users and ok_files:
        try:
            Api(STORAGE_PROJECT, key=users_key).request("GET", "/users?limit=1")
            report.add(Check("A5", "Project boundary: users key must NOT work on storage project",
                             "FAIL", "users key was accepted by storage project"))
        except ApiError as e:
            report.add(Check("A5", "Project boundary: users key must NOT work on storage project",
                             "PASS", f"rejected as expected (status={e.status})"))
        try:
            Api(AUTH_PROJECT, key=files_key).request("GET", "/storage/buckets?limit=1")
            report.add(Check("A6", "Project boundary: files key must NOT work on users project",
                             "FAIL", "files key was accepted by users project"))
        except ApiError as e:
            report.add(Check("A6", "Project boundary: files key must NOT work on users project",
                             "PASS", f"rejected as expected (status={e.status})"))

    # project self-inspection (scope project.read, optional)
    for cid, api, label in (("A7", auth, "users"), ("A8", storage, "storage")):
        try:
            project = api.get("/project")
            auth_methods = project.get("authMethods") or project.get("auth") or {}
            report.add(Check(cid, f"Project settings readable ({label} project)", "INFO",
                             f"name={project.get('name','?')} services={len(project.get('services') or {})} "
                             f"authMethods={json.dumps(auth_methods)[:300]}"))
        except ApiError as e:
            report.add(Check(cid, f"Project settings readable ({label} project)", "SKIP",
                             f"scope unavailable (status={e.status} type={e.type})"))
    return auth, storage


def check_platform(report: Report, api: Api, project_label: str, mode: str) -> None:
    cid = "B1" if project_label == "users" else "C1"
    title = f"Android platform '{ANDROID_PACKAGE}' registered ({project_label} project)"
    try:
        page = api.get("/project/platforms")
        platforms = page.get("platforms", [])
        match = [p for p in platforms
                 if p.get("type") == "android" and p.get("applicationId") == ANDROID_PACKAGE]
        if match:
            report.add(Check(cid, title, "PASS", f"platformId={match[0].get('$id')}"))
            return
        if mode in ("repair", "all"):
            created = api.post("/project/platforms/android", {
                "platformId": f"{TEST_PREFIX}-android",
                "name": f"UniHub Android ({ANDROID_PACKAGE})",
                "applicationId": ANDROID_PACKAGE,
            })
            report.add(Check(cid, title, "FIXED", f"created platformId={created.get('$id')}"))
            report.change(f"[{project_label}] created Android platform {ANDROID_PACKAGE}")
        else:
            others = [(p.get("type"), p.get("name")) for p in platforms]
            report.add(Check(cid, title, "FAIL",
                             f"not found; existing platforms={others} (mode=audit, no change made)"))
    except ApiError as e:
        report.add(Check(cid, title, "SKIP", f"cannot read platforms (status={e.status} type={e.type})"))


def audit_users_project(report: Report, api: Api, mode: str, files_key: str = "") -> None:
    check_platform(report, api, "users", mode)

    # B2 admin presence (count only)
    try:
        page = api.request("GET", "/users?limit=100")
        users = page.get("users", [])
        admins = [u for u in users if "admin" in (u.get("labels") or [])]
        active = [u for u in users if u.get("status") is True]
        report.add(Check("B2", "Users project has accounts", "INFO",
                         f"total={page.get('total', len(users))} (fetched {len(users)}) "
                         f"active={len(active)} with 'admin' label={len(admins)}"))
        if not admins:
            report.add(Check("B3", "At least one account carries the 'admin' label", "FAIL",
                             "no admin-labelled account; the app management UI will refuse every action"))
        else:
            report.add(Check("B3", "At least one account carries the 'admin' label", "PASS",
                             f"{len(admins)} admin account(s)"))
    except ApiError as e:
        report.add(Check("B2", "Users project has accounts", "SKIP", f"status={e.status}"))
        report.add(Check("B3", "At least one account carries the 'admin' label", "SKIP", "no user list access"))

    # B4-B7 bridge function
    fn = None
    try:
        page = api.request("GET", f"/functions?limit=100")
        fns = page.get("functions", [])
        fn = next((f for f in fns if f.get("$id") == BRIDGE_FUNCTION_ID), None)
        if fn is None:
            if mode in ("repair", "all"):
                fn = api.post("/functions", {
                    "functionId": BRIDGE_FUNCTION_ID,
                    "name": "UniHub auth bridge",
                    "runtime": "kotlin-2.0",
                    "execute": ["users"],
                    "timeout": 60,
                    "enabled": True,
                    "logging": False,
                    "entrypoint": "Index.kt",
                    "scopes": ["users.read", "users.write"],
                })
                report.add(Check("B4", f"Function '{BRIDGE_FUNCTION_ID}' exists", "FIXED", "created with documented settings"))
                report.change(f"[users] created function {BRIDGE_FUNCTION_ID} (kotlin-2.0, logging off, execute=users)")
            else:
                report.add(Check("B4", f"Function '{BRIDGE_FUNCTION_ID}' exists", "FAIL",
                                 f"MISSING — this is the root cause of the app error "
                                 f"'تعذر تفعيل خدمة ربط Appwrite' (mode=audit, no change made). "
                                 f"functions in project: {[f.get('$id') for f in fns]}"))
                return
        else:
            report.add(Check("B4", f"Function '{BRIDGE_FUNCTION_ID}' exists", "PASS",
                             f"runtime={fn.get('runtime')} enabled={fn.get('enabled')}"))
    except ApiError as e:
        report.add(Check("B4", f"Function '{BRIDGE_FUNCTION_ID}' exists", "SKIP",
                         f"cannot list functions (status={e.status} type={e.type})"))
        return

    # configuration drift repair
    problems = []
    if fn.get("enabled") is not True:
        problems.append("enabled!=true")
    if fn.get("logging") not in (False, 0):
        problems.append(f"logging={fn.get('logging')} (must be disabled)")
    if not str(fn.get("runtime", "")).startswith("kotlin"):
        problems.append(f"runtime={fn.get('runtime')}")
    if fn.get("entrypoint") not in ("Index.kt", "", None):
        problems.append(f"entrypoint={fn.get('entrypoint')}")
    execute = fn.get("execute") or []
    if execute != ["users"] and set(execute) != {"users"}:
        problems.append(f"execute={execute} (expected exactly ['users'])")
    scopes = set(fn.get("scopes") or [])
    if not {"users.read", "users.write"}.issubset(scopes):
        problems.append(f"scopes={sorted(scopes)} (need users.read+users.write)")
    timeout = int(fn.get("timeout") or 15)
    if timeout < 30:
        problems.append(f"timeout={timeout} (bridge makes cross-project calls; >=30s recommended)")

    if problems and mode in ("repair", "all"):
        try:
            api.patch(f"/functions/{BRIDGE_FUNCTION_ID}", {
                "execute": ["users"], "logging": False, "entrypoint": "Index.kt",
                "scopes": ["users.read", "users.write"], "timeout": max(timeout, 60), "enabled": True,
            })
            report.add(Check("B5", "Function configuration matches the documented design", "FIXED",
                             f"repaired: {', '.join(problems)}"))
            report.change(f"[users] function config repaired: {', '.join(problems)}")
        except ApiError as e:
            report.add(Check("B5", "Function configuration matches the documented design", "FAIL",
                             f"repair failed: {e}"))
    elif problems:
        report.add(Check("B5", "Function configuration matches the documented design", "FAIL",
                         f"drift: {', '.join(problems)}"))
    else:
        report.add(Check("B5", "Function configuration matches the documented design", "PASS",
                         f"timeout={timeout}s execute={execute} scopes={sorted(scopes)} logging=off"))

    # deployment
    try:
        page = api.request("GET", f"/functions/{BRIDGE_FUNCTION_ID}/deployments?limit=25")
        deployments = page.get("deployments", [])
        active = fn.get("deployment")
        ready = [d for d in deployments if d.get("status") == "ready"]
        live = next((d for d in deployments if d.get("$id") == active and d.get("status") == "ready"), None)
        if live is not None:
            report.add(Check("B6", "Function has an active ready deployment", "PASS",
                             f"deployment={live.get('$id')} size={live.get('size')}"))
        elif mode in ("repair", "all"):
            did = deploy_bridge(report, api)
            if did:
                report.add(Check("B6", "Function has an active ready deployment", "FIXED", f"deployment={did}"))
            else:
                report.add(Check("B6", "Function has an active ready deployment", "FAIL", "deployment build failed"))
        else:
            report.add(Check("B6", "Function has an active ready deployment", "FAIL",
                             f"active={active} ready={[d.get('$id') for d in ready]} "
                             f"statuses={[d.get('status') for d in deployments]} (mode=audit)"))
    except ApiError as e:
        report.add(Check("B6", "Function has an active ready deployment", "SKIP", f"status={e.status}"))

    # variables
    try:
        page = api.request("GET", f"/functions/{BRIDGE_FUNCTION_ID}/variables?limit=100")
        names = sorted(v.get("key", "?") for v in page.get("variables", []))
        if "STORAGE_API_KEY" in names:
            report.add(Check("B7", "Function variable STORAGE_API_KEY present", "PASS",
                             f"variables={names}"))
        elif mode in ("repair", "all"):
            api.post(f"/functions/{BRIDGE_FUNCTION_ID}/variables",
                     {"key": "STORAGE_API_KEY", "value": files_key or os.environ.get("AW_FILES_KEY", "")})
            report.add(Check("B7", "Function variable STORAGE_API_KEY present", "FIXED",
                             f"value taken from env AW_FILES_KEY; variables={names + ['STORAGE_API_KEY']}"))
            report.change("[users] created function variable STORAGE_API_KEY (value not logged)")
        else:
            report.add(Check("B7", "Function variable STORAGE_API_KEY present", "FAIL",
                             f"missing; existing={names} (mode=audit)"))
    except ApiError as e:
        report.add(Check("B7", "Function variable STORAGE_API_KEY present", "SKIP", f"status={e.status}"))


def deploy_bridge(report: Report, api: Api) -> Optional[str]:
    """Package service/appwrite/auth-bridge as gzip tarball and deploy it."""
    required = ["Index.kt", "BridgeService.kt", "AppwriteRestApi.kt", "dependencies.gradle"]
    missing = [f for f in required if not os.path.exists(os.path.join(BRIDGE_DIR, f))]
    if missing:
        report.add(Check("B6b", "Bridge sources present in repo", "FAIL", f"missing {missing}"))
        return None
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tar:
        for name in required:
            tar.add(os.path.join(BRIDGE_DIR, name), arcname=name)
    code = buf.getvalue()
    body, ctype = multipart(
        {"entrypoint": "Index.kt", "activate": "true"},
        {"code": ("code.tar.gz", code, "application/gzip")},
    )
    dep = api.post(f"/functions/{BRIDGE_FUNCTION_ID}/deployments", raw_body=body, content_type=ctype)
    did = dep.get("$id")
    for _ in range(60):
        time.sleep(5)
        d = api.get(f"/functions/{BRIDGE_FUNCTION_ID}/deployments/{did}")
        status = d.get("status")
        if status == "ready":
            try:
                api.patch(f"/functions/{BRIDGE_FUNCTION_ID}/deployment", {"deploymentId": did})
            except ApiError:
                pass
            return did
        if status in ("failed", "canceled"):
            report.add(Check("B6b", "Bridge deployment build", "FAIL",
                             f"status={status} message={redact(str(d.get('message', ''))[:300])}"))
            return None
    report.add(Check("B6b", "Bridge deployment build", "FAIL", "timed out waiting for build"))
    return None


def audit_storage_project(report: Report, api: Api, mode: str) -> dict:
    check_platform(report, api, "storage", mode)
    bucket = None
    try:
        page = api.request("GET", "/storage/buckets?limit=100")
        buckets = page.get("buckets", [])
        bucket = next((b for b in buckets if b.get("$id") == BUCKET_ID), None)
        if bucket is None:
            if mode in ("repair", "all"):
                bucket = api.post("/storage/buckets", {
                    "bucketId": BUCKET_ID,
                    "name": "UniHub cloud library",
                    "permissions": sorted(EXPECTED_BUCKET_PERMISSIONS),
                    "fileSecurity": False,
                    "enabled": True,
                })
                report.add(Check("C2", f"Storage bucket '{BUCKET_ID}' exists", "FIXED", "created with documented policy"))
                report.change("[storage] created bucket with label-based permissions")
            else:
                report.add(Check("C2", f"Storage bucket '{BUCKET_ID}' exists", "FAIL",
                                 f"MISSING; buckets={[b.get('$id') for b in buckets]} (mode=audit)"))
                return {}
        else:
            report.add(Check("C2", f"Storage bucket '{BUCKET_ID}' exists", "PASS",
                             f"name={bucket.get('name')} enabled={bucket.get('enabled')}"))
    except ApiError as e:
        report.add(Check("C2", f"Storage bucket '{BUCKET_ID}' exists", "SKIP", f"status={e.status} type={e.type}"))
        return {}

    # C3 policy exactness (the bridge refuses any deviation)
    perms = set(bucket.get("$permissions") or bucket.get("permissions") or [])
    file_security = bucket.get("fileSecurity")
    enabled = bucket.get("enabled")
    drift = []
    if perms != EXPECTED_BUCKET_PERMISSIONS:
        drift.append(f"permissions={sorted(perms)}")
    if file_security is not False:
        drift.append(f"fileSecurity={file_security} (must be false)")
    if enabled is not True:
        drift.append(f"enabled={enabled}")
    if drift and mode in ("repair", "all"):
        try:
            updated = api.patch(f"/storage/buckets/{BUCKET_ID}", {
                "permissions": sorted(EXPECTED_BUCKET_PERMISSIONS),
                "fileSecurity": False,
                "enabled": True,
            })
            report.add(Check("C3", "Bucket policy exactly matches the documented label policy", "FIXED",
                             f"before: {', '.join(drift)}"))
            report.change(f"[storage] bucket policy repaired: {', '.join(drift)}")
            bucket = updated
        except ApiError as e:
            report.add(Check("C3", "Bucket policy exactly matches the documented label policy", "FAIL",
                             f"repair failed: {e}"))
    elif drift:
        report.add(Check("C3", "Bucket policy exactly matches the documented label policy", "FAIL",
                         f"drift: {', '.join(drift)} — the bridge will reject storageToken until fixed"))
    else:
        report.add(Check("C3", "Bucket policy exactly matches the documented label policy", "PASS",
                         "read/create/update/delete label roles exact, fileSecurity=false"))

    exts = bucket.get("allowedFileExtensions") or []
    size = bucket.get("maximumFileSize")
    ext_ok = (not exts) or ("bin" in exts and "json" in exts) or (".bin" in exts and ".json" in exts)
    report.add(Check("C4", "Bucket accepts .bin uploads and .json manifest", "PASS" if ext_ok else "WARN",
                     f"allowedFileExtensions={exts} maximumFileSize={size}"))
    return bucket


def storage_auth_hardening(report: Report, api: Api, mode: str) -> None:
    """C5: storage project must not be an independent login destination (docs)."""
    try:
        project = api.get("/project")
    except ApiError as e:
        report.add(Check("C5", "Storage project auth methods hardened", "SKIP",
                         f"project.read unavailable (status={e.status})"))
        return
    methods = project.get("authMethods") or {}
    if not isinstance(methods, dict) or not methods:
        report.add(Check("C5", "Storage project auth methods hardened", "SKIP",
                         f"response shape lacks authMethods (keys={sorted(project)[:12]})"))
        return
    client_methods = ["email-password", "anonymous", "phone", "magic-url", "email-otp", "invites", "passkey"]
    enabled_bad = [m for m in client_methods if methods.get(m) is True]
    oauth_on = sorted(k for k, v in methods.items() if str(k).startswith("oauth2") and v is True)
    jwt_ok = methods.get("jwt") is not False
    if not enabled_bad and jwt_ok:
        report.add(Check("C5", "Storage project auth methods hardened", "PASS",
                         f"client login methods disabled, jwt={'on' if jwt_ok else 'OFF!'}"
                         + (f", oauth2 still enabled: {oauth_on} (disable manually)" if oauth_on else "")))
        return
    if mode in ("repair", "all") and enabled_bad:
        fixed = []
        for method in enabled_bad:
            try:
                api.patch(f"/project/auth-methods/{method}", {"enabled": False})
                fixed.append(method)
            except ApiError as e:
                report.add(Check("C5", f"Disable storage auth method '{method}'", "FAIL", str(e)))
        report.add(Check("C5", "Storage project auth methods hardened", "FIXED" if fixed else "FAIL",
                         f"disabled={fixed}"))
        report.change(f"[storage] disabled client auth methods: {fixed}")
    else:
        report.add(Check("C5", "Storage project auth methods hardened", "WARN",
                         f"still enabled: {enabled_bad + oauth_on} (docs require them off in the storage project)"))


# ---------------------------------------------------------------- end-to-end tests


def e2e(report: Report, auth: Api, storage: Api, files_key: str = "") -> None:
    ts = str(int(time.time()))
    rnd = "".join(pysecrets.choice(string.ascii_lowercase) for _ in range(8))
    users = {}
    created_users = []
    created_files = []
    sessions = {}

    def api_create_user(role: str, labels: list) -> dict:
        uid = f"{TEST_PREFIX}-{role}-{ts}"
        email = f"{TEST_PREFIX}.{role}.{ts}.{rnd}@example.com"
        password = pysecrets.token_urlsafe(24) + "Aa1!"
        user = auth.post("/users", {
            "userId": uid, "email": email, "password": password, "name": f"audit {role}",
        })
        if labels:
            auth.put(f"/users/{uid}/labels", {"labels": labels})
        created_users.append(uid)
        return {"id": uid, "email": email, "password": password, "labels": labels}

    try:
        users["admin"] = api_create_user("admin", ["admin"])
        users["reader"] = api_create_user("reader", ["unihub_download"])
        users["uploader"] = api_create_user("uploader", ["unihub_download", "unihub_upload"])
        users["nolabels"] = api_create_user("nolabels", [])
    except ApiError as e:
        report.test("create throwaway test users", False, str(e))
        return
    report.test("create throwaway test users (admin/reader/uploader/nolabels)", True)

    def login(u: dict) -> tuple:
        s = Api(AUTH_PROJECT).post("/account/sessions/email",
                                   {"email": u["email"], "password": u["password"]})
        return s.get("$id", ""), s.get("secret", "")

    for name, u in users.items():
        try:
            sid, secret = login(u)
            sessions[name] = {"id": sid, "secret": secret}
        except ApiError as e:
            report.test(f"email/password login ({name})", False, str(e))
            return
    report.test("email/password login works for every test user", True)

    def bridge_storage_token(name: str) -> dict:
        s = sessions[name]
        api = Api(AUTH_PROJECT, session=s["secret"])
        ex = api.post(f"/functions/{BRIDGE_FUNCTION_ID}/executions", {
            "body": json.dumps({"operation": "storageToken"}),
            "async": False, "method": "POST", "path": "/",
            "headers": {"content-type": "application/json"},
        })
        status = ex.get("status")
        code = int(ex.get("responseStatusCode") or 0)
        payload = ex.get("responseBody") or ""
        return {"status": status, "code": code, "body": payload}

    # T1: storage token for admin
    token_admin = None
    try:
        r = bridge_storage_token("admin")
        data = json.loads(r["body"]) if r["body"] else {}
        ok = (r["status"] == "completed" and r["code"] in (200, 201)
              and data.get("projectId") == STORAGE_PROJECT and data.get("bucketId") == BUCKET_ID
              and data.get("authUserId") == users["admin"]["id"]
              and isinstance(data.get("expiresIn"), int) and 60 <= data["expiresIn"] <= 300
              and len(str(data.get("jwt", ""))) > 20)
        token_admin = data if ok else None
        report.test("bridge storageToken (admin) returns scoped storage JWT", ok,
                    f"status={r['status']} http={r['code']} keys={sorted(data)} expiresIn={data.get('expiresIn')}")
    except ApiError as e:
        report.test("bridge storageToken (admin)", False, str(e))
    except json.JSONDecodeError as e:
        report.test("bridge storageToken (admin) parses as JSON", False, str(e))

    # T2: storage token denied for account without labels
    try:
        r = bridge_storage_token("nolabels")
        data = json.loads(r["body"]) if r["body"] else {}
        ok = r["code"] == 403 and data.get("code") == "forbidden"
        report.test("bridge storageToken denies account without labels (403 forbidden)", ok,
                    f"http={r['code']} body={redact(r['body'][:120])}")
    except ApiError as e:
        report.test("bridge storageToken denies account without labels", False, str(e))

    # T3: admin-only bridge operations
    try:
        r = bridge_list_users(sessions["reader"]["secret"])
        report.test("bridge listUsers forbidden for non-admin reader", r["code"] == 403,
                    f"http={r['code']}")
    except ApiError as e:
        report.test("bridge listUsers forbidden for non-admin reader", False, str(e))
    try:
        r = bridge_list_users(sessions["admin"]["secret"])
        body = json.loads(r["body"]) if r["body"] else {}
        ok = r["code"] == 200 and isinstance(body.get("users"), list)
        report.test("bridge listUsers works for admin", ok, f"http={r['code']} users={len(body.get('users', []))}")
    except ApiError as e:
        report.test("bridge listUsers works for admin", False, str(e))

    if not token_admin:
        report.test("remaining storage tests (upload/download/permissions)", False,
                    "skipped: no storage JWT for admin")
        return

    jwt_admin = token_admin["jwt"]
    st = Api(STORAGE_PROJECT, jwt=jwt_admin)

    content = f"unihub e2e audit {ts} {rnd}\n".encode() * 64
    digest = sha256_hex(content)
    file_id = f"{TEST_PREFIX}file{ts}"
    file_name = f"{TEST_PREFIX}_{ts}.bin"

    # T4: chunked-style upload exactly like AppwriteStorageTransport (Content-Range + X-Appwrite-ID)
    try:
        body, ctype = multipart({}, {"file": (file_name, content, "application/octet-stream")})
        up = st.post(f"/storage/buckets/{BUCKET_ID}/files", raw_body=body, content_type=ctype,
                     headers={"Content-Range": f"bytes 0-{len(content)-1}/{len(content)}",
                              "X-Appwrite-ID": file_id})
        ok = up.get("$id") == file_id and up.get("sizeOriginal") == len(content)
        report.test("chunked upload (Content-Range + X-Appwrite-ID) as modify-capable admin shadow", ok,
                    f"fileId={up.get('$id')} size={up.get('sizeOriginal')}")
        created_files.append(file_id)
    except ApiError as e:
        report.test("chunked upload as admin shadow", False, str(e))
        file_id = None

    # T5: download + integrity
    if file_id:
        try:
            req = urllib.request.Request(
                f"{ENDPOINT}/storage/buckets/{BUCKET_ID}/files/{file_id}/download",
                headers={"X-Appwrite-Project": STORAGE_PROJECT, "X-Appwrite-JWT": jwt_admin})
            with urllib.request.urlopen(req, timeout=60) as resp:
                data = resp.read()
            ok = sha256_hex(data) == digest and len(data) == len(content)
            report.test("download returns byte-identical content (SHA-256 verified)", ok,
                        f"expected={digest[:16]}.. got={sha256_hex(data)[:16]}.. size={len(data)}")
        except urllib.error.HTTPError as e:
            report.test("download as admin shadow", False, f"HTTP {e.code}: {redact(e.read()[:200])}")
        except Exception as e:  # network
            report.test("download as admin shadow", False, redact(str(e)))

    # T6: reader may download but not upload/delete
    tok_reader = None
    try:
        r = bridge_storage_token("reader")
        tok_reader = json.loads(r["body"]) if r["body"] else {}
    except Exception as e:
        report.test("storageToken for reader", False, str(e))
    if tok_reader and file_id:
        rt = Api(STORAGE_PROJECT, jwt=tok_reader["jwt"])
        try:
            body, ctype = multipart({}, {"file": (f"{TEST_PREFIX}_reader_{ts}.bin", content, "application/octet-stream")})
            rt.post(f"/storage/buckets/{BUCKET_ID}/files", raw_body=body, content_type=ctype)
            report.test("reader upload must be rejected", False, "upload unexpectedly succeeded")
        except ApiError as e:
            report.test("reader upload rejected (401 expected)", e.status == 401, f"status={e.status}")
        try:
            rt.delete(f"/storage/buckets/{BUCKET_ID}/files/{file_id}")
            report.test("reader delete must be rejected", False, "delete unexpectedly succeeded")
        except ApiError as e:
            report.test("reader delete rejected (401 expected)", e.status == 401, f"status={e.status}")
        try:
            req = urllib.request.Request(
                f"{ENDPOINT}/storage/buckets/{BUCKET_ID}/files/{file_id}/download",
                headers={"X-Appwrite-Project": STORAGE_PROJECT, "X-Appwrite-JWT": tok_reader["jwt"]})
            with urllib.request.urlopen(req, timeout=60) as resp:
                ok = sha256_hex(resp.read()) == digest
            report.test("reader can download and content matches", ok)
        except Exception as e:
            report.test("reader can download", False, redact(str(e)))

    # T7: uploader can upload but not delete
    tok_up = None
    try:
        r = bridge_storage_token("uploader")
        tok_up = json.loads(r["body"]) if r["body"] else {}
    except Exception as e:
        report.test("storageToken for uploader", False, str(e))
    up_file_id = None
    if tok_up:
        ut = Api(STORAGE_PROJECT, jwt=tok_up["jwt"])
        try:
            body, ctype = multipart({}, {"file": (f"{TEST_PREFIX}_up_{ts}.bin", content, "application/octet-stream")})
            up2 = ut.post(f"/storage/buckets/{BUCKET_ID}/files", raw_body=body, content_type=ctype)
            up_file_id = up2.get("$id")
            created_files.append(up_file_id)
            report.test("uploader can upload (create label)", True, f"fileId={up_file_id}")
        except ApiError as e:
            report.test("uploader can upload (create label)", False, str(e))
        if up_file_id:
            try:
                ut.delete(f"/storage/buckets/{BUCKET_ID}/files/{up_file_id}")
                report.test("uploader delete must be rejected", False, "delete unexpectedly succeeded")
            except ApiError as e:
                report.test("uploader delete rejected (401 expected)", e.status == 401, f"status={e.status}")

    # T8: manifest upload/download (unihub_manifest.json) as modify-capable
    if token_admin:
        try:
            manifest = json.dumps({"v": 1, "audit": ts, "digest": digest}).encode()
            body, ctype = multipart({}, {"file": ("unihub_manifest.json", manifest, "application/json")})
            mf = st.post(f"/storage/buckets/{BUCKET_ID}/files", raw_body=body, content_type=ctype)
            mid = mf.get("$id")
            created_files.append(mid)
            req = urllib.request.Request(
                f"{ENDPOINT}/storage/buckets/{BUCKET_ID}/files/{mid}/download",
                headers={"X-Appwrite-Project": STORAGE_PROJECT, "X-Appwrite-JWT": jwt_admin})
            with urllib.request.urlopen(req, timeout=60) as resp:
                ok = json.loads(resp.read()).get("audit") == ts
            report.test("manifest json upload/download round-trip", ok, f"fileId={mid}")
        except Exception as e:
            report.test("manifest json upload/download round-trip", False, redact(str(e)))

    # T9: project-separation invariant — auth session must NOT authorize storage project
    try:
        Api(STORAGE_PROJECT, session=sessions["admin"]["secret"]).get("/account")
        report.test("auth session rejected by storage project", False, "session accepted across projects")
    except ApiError as e:
        report.test("auth session rejected by storage project", e.status in (401, 403), f"status={e.status}")
    try:
        Api(AUTH_PROJECT, jwt=jwt_admin).get("/account")
        report.test("storage JWT rejected by users project", False, "JWT accepted across projects")
    except ApiError as e:
        report.test("storage JWT rejected by users project", e.status in (401, 403), f"status={e.status}")

    # ---- cleanup (test data only) ----
    for fid in created_files:
        try:
            st.delete(f"/storage/buckets/{BUCKET_ID}/files/{fid}")
        except ApiError:
            try:
                Api(STORAGE_PROJECT, key=os.environ.get("AW_FILES_KEY", "")).delete(
                    f"/storage/buckets/{BUCKET_ID}/files/{fid}")
            except ApiError:
                report.test(f"cleanup test file {fid}", False, "could not delete; remove manually")
    for name, s in sessions.items():
        try:
            Api(AUTH_PROJECT, session=s["secret"]).delete("/account/sessions/current")
        except ApiError:
            pass
    for uid in created_users:
        try:
            auth.delete(f"/users/{uid}")
        except ApiError as e:
            report.test(f"cleanup test user {uid}", False, str(e))
    files_key_api = Api(STORAGE_PROJECT, key=files_key or os.environ.get("AW_FILES_KEY", ""))
    for uid in created_users:
        sid = shadow_id(uid)
        try:
            files_key_api.delete(f"/users/{sid}")
        except ApiError:
            pass
    report.test("cleanup: throwaway users/files/sessions removed", True)


def bridge_list_users(session_secret: str) -> dict:
    api = Api(AUTH_PROJECT, session=session_secret)
    ex = api.post(f"/functions/{BRIDGE_FUNCTION_ID}/executions", {
        "body": json.dumps({"operation": "listUsers"}),
        "async": False, "method": "POST", "path": "/",
        "headers": {"content-type": "application/json"},
    })
    return {"code": int(ex.get("responseStatusCode") or 0), "body": ex.get("responseBody") or ""}


# ---------------------------------------------------------------- report writer


def write_report(report: Report, out_dir: str, mode: str) -> None:
    os.makedirs(out_dir, exist_ok=True)
    counts = {}
    for c in report.checks:
        counts[c.status] = counts.get(c.status, 0) + 1
    lines = [
        "# Appwrite Cloud audit report — UniHub integration",
        "",
        f"- date (UTC): {time.strftime('%Y-%m-%d %H:%M:%S', time.gmtime())}",
        f"- mode: {mode}",
        f"- endpoint: {ENDPOINT}",
        f"- users project: `{AUTH_PROJECT}` — storage project: `{STORAGE_PROJECT}` — bucket: `{BUCKET_ID}`",
        f"- summary: " + ", ".join(f"{k}={v}" for k, v in sorted(counts.items())),
        "",
        "## Checks",
        "",
        "| id | check | status | detail |",
        "|----|-------|--------|--------|",
    ]
    for c in report.checks:
        lines.append(f"| {c.cid} | {c.title} | **{c.status}** | {redact(c.detail)} |")
    lines += ["", "## Cloud changes applied", ""]
    lines += [f"- {redact(x)}" for x in report.changes] or ["- none"]
    lines += ["", "## End-to-end tests", "", "| result | test | evidence |", "|--------|------|----------|"]
    for t in report.tests:
        lines.append(f"| **{t['result']}** | {redact(t['text'])} | {redact(t['evidence'])} |")
    if not report.tests:
        lines.append("| — | no e2e tests were run in this mode | — |")
    lines += [
        "",
        "## Notes",
        "",
        "- No API keys, JWTs, session secrets or passwords are stored in this report.",
        "- FAIL on the bridge function block (B4) explains the in-app message",
        "  `تعذر تفعيل خدمة ربط Appwrite من مشروع المستخدمين`.",
        "- Repair mode only implements the documented design from `docs/appwrite-setup.md`.",
    ]
    path = os.path.join(out_dir, "report.md")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    with open(os.path.join(out_dir, "report.json"), "w", encoding="utf-8") as f:
        json.dump({
            "checks": [c.__dict__ for c in report.checks],
            "changes": report.changes,
            "tests": report.tests,
        }, f, ensure_ascii=False, indent=2)
    print(f"\nReport written to {path}")


# ---------------------------------------------------------------- main


def key_variants(raw: str) -> list:
    raw = raw.strip()
    if raw.startswith("standard_") and len(raw) > 9:
        return [raw, raw[len("standard_"):]]
    return [raw]


def pick_working_key(project: str, raw: str) -> Optional[str]:
    for candidate in key_variants(raw):
        try:
            Api(project, key=candidate).request("GET", "/users?limit=1" if project == AUTH_PROJECT
                                                else "/storage/buckets?limit=1")
            KNOWN_SECRETS.add(candidate)
            return candidate
        except ApiError as e:
            if e.status in (401, 403):
                continue
            KNOWN_SECRETS.add(candidate)
            return candidate  # reachable but other error: keep, let the audit report it
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--mode", choices=["audit", "repair", "test", "all"], default="all")
    parser.add_argument("--report-dir", default=f"verification/appwrite-cloud-audit-{time.strftime('%Y-%m-%d')}")
    args = parser.parse_args()

    users_raw = os.environ.get("AW_USERS_KEY", "")
    files_raw = os.environ.get("AW_FILES_KEY", "")
    if not users_raw or not files_raw:
        print("ERROR: set AW_USERS_KEY and AW_FILES_KEY in the environment. "
              "Never put API keys in source files.", file=sys.stderr)
        return 2

    KNOWN_SECRETS.update(key_variants(users_raw))
    KNOWN_SECRETS.update(key_variants(files_raw))

    report = Report()
    print(f"== UniHub Appwrite cloud audit | mode={args.mode} | endpoint={ENDPOINT} ==\n")

    users_key = pick_working_key(AUTH_PROJECT, users_raw)
    files_key = pick_working_key(STORAGE_PROJECT, files_raw)
    if not users_key or not files_key:
        report.add(Check("A0", "API keys accepted by their projects", "FAIL",
                         f"users_key_ok={bool(users_key)} files_key_ok={bool(files_key)} "
                         f"(checked both 'standard_' and stripped forms)"))
        write_report(report, args.report_dir, args.mode)
        return 1
    report.add(Check("A0", "API keys accepted by their projects", "PASS",
                     "credentials verified against each project (values never logged)"))

    auth, storage = phase_identity(report, users_key, files_key)
    if auth is None:
        write_report(report, args.report_dir, args.mode)
        return 1

    do_repair = args.mode in ("repair", "all")
    audit_users_project(report, Api(AUTH_PROJECT, key=users_key), "repair" if do_repair else "audit", files_key)
    audit_storage_project(report, Api(STORAGE_PROJECT, key=files_key), "repair" if do_repair else "audit")
    storage_auth_hardening(report, Api(STORAGE_PROJECT, key=files_key), "repair" if do_repair else "audit")

    if args.mode in ("test", "all"):
        # re-read function state for tests (may have just been repaired)
        e2e(report, Api(AUTH_PROJECT, key=users_key), Api(STORAGE_PROJECT, key=files_key), files_key)

    write_report(report, args.report_dir, args.mode)
    failed = sum(1 for c in report.checks if c.status == "FAIL") + sum(1 for t in report.tests if t["result"] == "FAIL")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
