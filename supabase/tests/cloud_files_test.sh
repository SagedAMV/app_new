#!/usr/bin/env bash
# =====================================================================
#  UniHub — فحص حيّ لمسار الملفات السحابية (Storage + جداول + بيان)
#
#  التشغيل:
#    SUPABASE_URL=... SUPABASE_PUBLISHABLE_KEY=... SUPABASE_SERVICE_KEY=... \
#    bash supabase/tests/cloud_files_test.sh
#
#  هذا هو المسار الذي يمرّ به التطبيق فعليًا:
#    1) رفع بايتات إلى الحاوية (Storage) بمسار = معرّف الملف (ASCII).
#    2) صف في public.cloud_objects (سجل العنصر).
#    3) صف في public.cloud_manifests (البيان) — تعارض التفرّد = 409.
#    4) تنزيل كامل، وتنزيل Range (استئناف) = 206.
#    5) حذف.
#
#  ملاحظة تصميمية متحقَّق منها حيًّا: Storage يرفض المفاتيح غير اللاتينية
#  («Invalid key» ⇒ 400)، لذلك `storage_path` = معرّف الملف (f + sha256)،
#  والمفتاح المنطقي يبقى في `object_key`.
# =====================================================================
set -uo pipefail

URL="${SUPABASE_URL:?SUPABASE_URL مطلوب}"
PUB="${SUPABASE_PUBLISHABLE_KEY:?SUPABASE_PUBLISHABLE_KEY مطلوب}"
SEC="${SUPABASE_SERVICE_KEY:?SUPABASE_SERVICE_KEY مطلوب (إداري، لا يُحفظ في المستودع)}"
API="$URL/rest/v1"
AUTH="$URL/auth/v1"
STORAGE="$URL/storage/v1"
BUCKET="unihub-files"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

PASS=0
FAIL=0
ok()  { PASS=$((PASS + 1)); printf '  [PASS] %s\n' "$1"; }
bad() { FAIL=$((FAIL + 1)); printf '  [FAIL] %s — %s\n' "$1" "${2:-}"; }
expect() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "expected=$2 actual=$3"; fi; }
# رفض: PostgREST يعيد 401 لدور anon و403 للدور المُصادَق عليه عند خرق RLS.
# المرجع = «لم يُنشأ/لم يُعدَّل شيء»، لا رمز بعينه.
expect_any() { # name "codes space separated" actual
  for c in $2; do
    if [ "$c" = "$3" ]; then ok "$1"; return; fi
  done
  bad "$1" "expected one of [$2] actual=$3"
}
expect_deny() {
  case "$2" in
    401|403) ok "$1" ;;
    *) bad "$1" "expected=رفض(401/403) actual=$2" ;;
  esac
}

jget() { python3 -c '
import json,sys
try:
    d=json.load(open(sys.argv[1]))
except Exception:
    print(""); raise SystemExit
for k in sys.argv[2].split("."):
    if isinstance(d,list):
        if k.lstrip("-").isdigit():
            i=int(k)
            if i>=len(d): print(""); raise SystemExit
            d=d[i]; continue
        d=d[0] if d else {}
    if not isinstance(d,dict) or k not in d: print(""); raise SystemExit
    d=d[k]
print("" if d is None else d)' "$1" "$2"; }
jcount() { python3 -c '
import json,sys
try: d=json.load(open(sys.argv[1]))
except Exception: print(-1); raise SystemExit
print(len(d) if isinstance(d,list) else -1)' "$1"; }
jscalar() { python3 -c '
import json,sys
try: d=json.load(open(sys.argv[1]))
except Exception: print(""); raise SystemExit
print(d if not isinstance(d,(dict,list)) else json.dumps(d))' "$1"; }

rest() { # method path token [body]
  local method="$1" path="$2" token="$3" body="${4:-}" code
  if [ -n "$body" ]; then
    code=$(curl -s -o "$TMP/body" -w '%{http_code}' --max-time 30 -X "$method" "$API$path" \
      -H "apikey: $PUB" -H "Authorization: Bearer $token" -H 'Content-Type: application/json' -d "$body")
  else
    code=$(curl -s -o "$TMP/body" -w '%{http_code}' --max-time 30 -X "$method" "$API$path" \
      -H "apikey: $PUB" -H "Authorization: Bearer $token" -H 'Content-Type: application/json')
  fi
  printf '%s' "$code"
}
rest_admin() { # method path [body]
  local method="$1" path="$2" body="${3:-}" code
  if [ -n "$body" ]; then
    code=$(curl -s -o "$TMP/body" -w '%{http_code}' --max-time 30 -X "$method" "$API$path" \
      -H "apikey: $SEC" -H "Authorization: Bearer $SEC" -H 'Content-Type: application/json' -d "$body")
  else
    code=$(curl -s -o "$TMP/body" -w '%{http_code}' --max-time 30 -X "$method" "$API$path" \
      -H "apikey: $SEC" -H "Authorization: Bearer $SEC" -H 'Content-Type: application/json')
  fi
  printf '%s' "$code"
}
signin() {
  local code
  code=$(curl -s -o "$TMP/auth" -w '%{http_code}' --max-time 30 -X POST "$AUTH/token?grant_type=password" \
    -H "apikey: $PUB" -H 'Content-Type: application/json' \
    -d "$(printf '{"email":"%s","password":"%s"}' "$1" "$2")")
  if [ "$code" = "200" ]; then jget "$TMP/auth" "access_token"; fi
}
create_user() {
  local payload
  payload=$(printf '{"target_email":"%s","target_password":"%s","download":%s,"upload":%s,"modify":%s}' "$1" "$2" "$3" "$4" "$5")
  rest_admin POST /rpc/admin_create_user "$payload" >/dev/null
  jscalar "$TMP/body"
}
delete_user() { rest_admin POST /rpc/admin_delete_user "$(printf '{"target":"%s"}' "$1")" >/dev/null; }
storage_upload() { # token path file
  curl -s -o "$TMP/body" -w '%{http_code}' --max-time 60 -X POST "$STORAGE/object/$BUCKET/$2" \
    -H "apikey: $PUB" -H "Authorization: Bearer $1" -H 'Content-Type: application/octet-stream' \
    -H 'x-upsert: true' --data-binary "@$3"
}

EMAIL="files-$$@unihub.test"
PASSWORD="UniHub!Test#2026"
USER_ID=""
TOKEN=""
cleanup() { [ -n "$USER_ID" ] && delete_user "$USER_ID"; }
trap 'cleanup; rm -rf "$TMP"' EXIT

echo "== UniHub cloud files flow test =="
echo "المشروع: $URL"

USER_ID=$(create_user "$EMAIL" "$PASSWORD" true true true)
TOKEN=$(signin "$EMAIL" "$PASSWORD")
expect "إنشاء مستخدم بصلاحيات كاملة" "1" "$([ -n "$USER_ID" ] && echo 1 || echo 0)"
expect "تسجيل الدخول" "1" "$([ -n "$TOKEN" ] && echo 1 || echo 0)"

CONTENT="UniHub cloud file flow test payload"
printf '%s' "$CONTENT" > "$TMP/file.bin"
SHA=$(sha256sum "$TMP/file.bin" | cut -d' ' -f1)
SIZE=$(stat -c%s "$TMP/file.bin")
FILE_ID="f${SHA}"
STORAGE_PATH="$FILE_ID"
OBJECT_KEY="tests/$$/payload.bin"
GEN="$$"

echo "-- 1) الرفع إلى الحاوية --"
code=$(storage_upload "$TOKEN" "$STORAGE_PATH" "$TMP/file.bin")
expect "رفع البايتات = 200" "200" "$code"
code=$(curl -s -o "$TMP/got" -w '%{http_code}' --max-time 30 "$STORAGE/object/$BUCKET/$STORAGE_PATH" \
  -H "apikey: $PUB" -H "Authorization: Bearer $TOKEN")
expect "تنزيل الملف = 200" "200" "$code"
expect "المحتوى مطابق" "$CONTENT" "$(cat "$TMP/got")"
GOT_SHA=$(sha256sum "$TMP/got" | cut -d' ' -f1)
expect "بصمة الملف مطابقة" "$SHA" "$GOT_SHA"
code=$(storage_upload "$TOKEN" "ملف-عربي.bin" "$TMP/file.bin")
expect "مفتاح غير لاتيني في Storage = 400" "400" "$code"

echo "-- 2) سجل العنصر --"
code=$(rest POST /cloud_objects "$TOKEN" "$(printf '{"id":"%s","object_key":"%s","name":"payload.bin","size":%s,"mime_type":"application/octet-stream","storage_path":"%s","version":"v1","sha256":"%s"}' "$FILE_ID" "$OBJECT_KEY" "$SIZE" "$STORAGE_PATH" "$SHA")")
expect "إدراج cloud_objects = 201" "201" "$code"
code=$(rest GET "/cloud_objects?id=eq.$FILE_ID" "$TOKEN" "")
expect "قراءة السجل = 200" "200" "$code"
expect "المعرّف صحيح" "$FILE_ID" "$(jget "$TMP/body" "0.id")"
expect "البصمة صحيحة" "$SHA" "$(jget "$TMP/body" "0.sha256")"
code=$(rest GET "/cloud_objects?select=id&limit=50" "$TOKEN" "")
expect "سرد العناصر = 200" "200" "$code"
expect "العنصر ضمن القائمة" "1" "$([ "$(jcount "$TMP/body")" -ge 1 ] && echo 1 || echo 0)"

echo "-- 3) البيان (manifest) --"
MANIFEST_ID="m$(printf '%016d' "$GEN")_$(printf '%s' "$SHA" | cut -c1-17)"
code=$(rest POST /cloud_manifests "$TOKEN" "$(printf '{"id":"%s","generation":%s,"payload":{"file":"%s","sha256":"%s"},"size_bytes":%s}' "$MANIFEST_ID" "$GEN" "$FILE_ID" "$SHA" "$SIZE")")
expect "إدراج البيان = 201" "201" "$code"
code=$(rest POST /cloud_manifests "$TOKEN" "$(printf '{"id":"%s","generation":%s,"payload":{"file":"%s","sha256":"%s"},"size_bytes":%s}' "$MANIFEST_ID" "$GEN" "$FILE_ID" "$SHA" "$SIZE")")
expect "بيان مكرر = 409 (تعارض تفرّد)" "409" "$code"
code=$(rest GET /rpc/latest_manifest "$TOKEN" "")
expect "آخر بيان عبر RPC = 200" "200" "$code"

echo "-- 4) التنزيل باستئناف (Range) --"
code=$(curl -s -o "$TMP/part" -D "$TMP/head" -w '%{http_code}' --max-time 30 \
  -H "Range: bytes=0-5" "$STORAGE/object/$BUCKET/$STORAGE_PATH" \
  -H "apikey: $PUB" -H "Authorization: Bearer $TOKEN")
expect "طلب Range = 206" "206" "$code"
expect "جزء من الملف" "UniHub" "$(cat "$TMP/part")"
expect "ترويسة Content-Range" "1" "$(grep -qi 'content-range: bytes 0-5/' "$TMP/head" && echo 1 || echo 0)"

echo "-- 5) الحذف والتنظيف --"
code=$(curl -s -o "$TMP/body" -w '%{http_code}' --max-time 30 -X DELETE "$STORAGE/object/$BUCKET/$STORAGE_PATH" \
  -H "apikey: $PUB" -H "Authorization: Bearer $TOKEN")
expect "حذف الملف من الحاوية = 200" "200" "$code"
code=$(rest DELETE "/cloud_objects?id=eq.$FILE_ID" "$TOKEN" "")
expect "حذف سجل العنصر = 204" "204" "$code"
code=$(rest DELETE "/cloud_manifests?id=eq.$MANIFEST_ID" "$TOKEN" "")
expect "حذف البيان = 204" "204" "$code"
code=$(curl -s -o "$TMP/body" -w '%{http_code}' --max-time 30 "$STORAGE/object/$BUCKET/$STORAGE_PATH" \
  -H "apikey: $PUB" -H "Authorization: Bearer $TOKEN")
expect_any "الملف بعد الحذف غير موجود" "400 404" "$code"

echo
echo "النتيجة: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
