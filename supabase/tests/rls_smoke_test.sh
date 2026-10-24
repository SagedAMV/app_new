#!/usr/bin/env bash
# =====================================================================
#  UniHub — فحص حيّ لسياسات RLS على مشروع Supabase
#
#  التشغيل (لا تُحفظ الأسرار في المستودع):
#    SUPABASE_URL=https://xxx.supabase.co \
#    SUPABASE_PUBLISHABLE_KEY=sb_publishable_... \
#    SUPABASE_SERVICE_KEY=sb_secret_... \
#    bash supabase/tests/rls_smoke_test.sh
#
#  المرجع (oracle) لكل فحص:
#    - الوصول يُمنع في القاعدة: الطلب المحظور يعيد 403 عند INSERT/UPDATE
#      عبر WITH CHECK، بينما SELECT/DELETE المحظورة تُنتج «صفر صفوف»
#      (200 + مصفوفة فارغة) لأن RLS ترشّح الصفوف بصمت.
#    - المستخدم العادي يرى صفَّه فقط في profiles.
#    - service_role يتجاوز RLS (لذلك يُستخدم للإدارة فقط).
# =====================================================================
set -uo pipefail

URL="${SUPABASE_URL:?SUPABASE_URL مطلوب}"
PUB="${SUPABASE_PUBLISHABLE_KEY:?SUPABASE_PUBLISHABLE_KEY مطلوب}"
SEC="${SUPABASE_SERVICE_KEY:?SUPABASE_SERVICE_KEY مطلوب (إداري، لا يُحفظ في المستودع)}"
API="$URL/rest/v1"
AUTH="$URL/auth/v1"
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

rest() { # method path token [body] -> رمز HTTP في stdout، والجسم في $TMP/body
  local method="$1" path="$2" token="$3" body="${4:-}" code
  if [ -n "$body" ]; then
    code=$(curl -s -o "$TMP/body" -w '%{http_code}' --max-time 30 -X "$method" "$API$path" \
      -H "apikey: $PUB" ${token:+-H "Authorization: Bearer $token"} -H 'Content-Type: application/json' -d "$body")
  else
    code=$(curl -s -o "$TMP/body" -w '%{http_code}' --max-time 30 -X "$method" "$API$path" \
      -H "apikey: $PUB" ${token:+-H "Authorization: Bearer $token"} -H 'Content-Type: application/json')
  fi
  printf '%s' "$code"
}
rest_admin() { # method path body — برمز service_role (يتجاوز RLS)
  local method="$1" path="$2" body="${3:-}" code
  code=$(curl -s -o "$TMP/body" -w '%{http_code}' --max-time 30 -X "$method" "$API$path" \
    -H "apikey: $SEC" -H "Authorization: Bearer $SEC" -H 'Content-Type: application/json' -d "$body")
  printf '%s' "$code"
}
signin() { # email password -> رمز الوصول
  curl -s -o "$TMP/auth" --max-time 30 -X POST "$AUTH/token?grant_type=password" \
    -H "apikey: $PUB" -H 'Content-Type: application/json' \
    -d "$(printf '{"email":"%s","password":"%s"}' "$1" "$2")"
  jget "$TMP/auth" "access_token"
}
create_user() { # email password download upload modify -> uuid
  local payload
  payload=$(printf '{"target_email":"%s","target_password":"%s","download":%s,"upload":%s,"modify":%s}' "$1" "$2" "$3" "$4" "$5")
  rest_admin POST /rpc/admin_create_user "$payload" >/dev/null
  jscalar "$TMP/body"
}
delete_user() { rest_admin POST /rpc/admin_delete_user "$(printf '{"target":"%s"}' "$1")" >/dev/null; }
storage_list() { # token -> رمز HTTP
  curl -s -o "$TMP/body" -w '%{http_code}' --max-time 30 -X POST "$STORAGE_URL/object/list/$BUCKET" \
    -H "apikey: $PUB" -H "Authorization: Bearer $1" -H 'Content-Type: application/json' \
    -d '{"prefix":"","limit":100}'
}
STORAGE_URL="$URL/storage/v1"

EMAIL_A="rls-reader-$$@unihub.test"
EMAIL_B="rls-full-$$@unihub.test"
EMAIL_C="rls-admin-$$@unihub.test"
EMAIL_D="rls-none-$$@unihub.test"
PASSWORD="UniHub!Test#2026"
UID_A=""; UID_B=""; UID_C=""; UID_D=""
TOKEN_A=""; TOKEN_B=""; TOKEN_C=""; TOKEN_D=""

cleanup() {
  for id in "$UID_A" "$UID_B" "$UID_C" "$UID_D"; do
    [ -n "$id" ] && delete_user "$id"
  done
  # إزالة مخلفات البيان التي أنشأها الفحص (لا تُترك في القاعدة)
  [ -n "${MANIFEST_ID:-}" ] && rest_admin DELETE "/cloud_manifests?id=eq.$MANIFEST_ID" >/dev/null
  [ -n "${DIGEST_RUN:-}" ] && rest_admin DELETE "/cloud_manifests?id=eq.m$DIGEST_RUN" >/dev/null
  rest_admin DELETE "/cloud_objects?object_key=like.rls/*" >/dev/null
}
trap 'cleanup; rm -rf "$TMP"' EXIT

echo "== UniHub RLS smoke test =="
echo "المشروع: $URL"

echo "-- إنشاء مستخدمين للاختبار --"
UID_A=$(create_user "$EMAIL_A" "$PASSWORD" true false false)
UID_B=$(create_user "$EMAIL_B" "$PASSWORD" true true true)
UID_C=$(create_user "$EMAIL_C" "$PASSWORD" true true true)
UID_D=$(create_user "$EMAIL_D" "$PASSWORD" false false false)
for pair in "A:$UID_A" "B:$UID_B" "C:$UID_C" "D:$UID_D"; do
  label="${pair%%:*}"; id="${pair##*:}"
  if [ -z "$id" ]; then bad "إنشاء مستخدم $label" "لم يُرجَع معرّف"; else ok "إنشاء مستخدم $label ($id)"; fi
done
# ترقية C إلى مشرف
rest_admin PATCH "/profiles?id=eq.$UID_C" '{"is_admin":true}' >/dev/null

TOKEN_A=$(signin "$EMAIL_A" "$PASSWORD")
TOKEN_B=$(signin "$EMAIL_B" "$PASSWORD")
TOKEN_C=$(signin "$EMAIL_C" "$PASSWORD")
TOKEN_D=$(signin "$EMAIL_D" "$PASSWORD")
for pair in "A:$TOKEN_A" "B:$TOKEN_B" "C:$TOKEN_C" "D:$TOKEN_D"; do
  label="${pair%%:*}"; tok="${pair##*:}"
  if [ -z "$tok" ]; then bad "تسجيل الدخول $label" "لا رمز"; else ok "تسجيل الدخول $label"; fi
done

echo "-- 1) الوصول المجهول --"
code=$(rest GET /profiles "$PUB" "")
expect "مجهول: قراءة profiles = 200" "200" "$code"
expect "مجهول: لا يرى صفوفًا" "0" "$(jcount "$TMP/body")"
code=$(rest POST /profiles "$PUB" '{"id":"00000000-0000-0000-0000-000000000000","email":"x@y.z"}')
expect_deny "مجهول: كتابة profiles مرفوضة" "$code"

echo "-- 2) المستخدم العادي (قراءة فقط) --"
code=$(rest GET /profiles "$TOKEN_A" "")
expect "قارئ: قراءة profiles = 200" "200" "$code"
expect "قارئ: يرى صفَّه فقط" "1" "$(jcount "$TMP/body")"
code=$(rest GET "/profiles?id=eq.$UID_B" "$TOKEN_A" "")
expect "قارئ: لا يرى صفَّ غيره" "0" "$(jcount "$TMP/body")"
code=$(rest PATCH "/profiles?id=eq.$UID_A" "$TOKEN_A" '{"display_name":"اسم محدَّث"}')
expect "قارئ: يعدّل اسمه = 204" "204" "$code"
code=$(rest PATCH "/profiles?id=eq.$UID_A" "$TOKEN_A" '{"can_upload":true}')
expect "قارئ: لا يعدّل صلاحياته = 403" "403" "$code"

echo "-- 3) cloud_objects --"
code=$(rest POST /cloud_objects "$TOKEN_B" "$(printf '{"id":"f%s","object_key":"rls/obj-%s","name":"obj","size":1,"mime_type":"text/plain","storage_path":"f%s","version":"v1","sha256":"abc"}' "$UID_B" "$UID_B" "$UID_B")")
expect "كامل: ينشئ cloud_objects = 201" "201" "$code"
code=$(rest GET "/cloud_objects?object_key=eq.rls/obj-$UID_B" "$TOKEN_A" "")
expect "قارئ: يرى العنصر (صلاحية تنزيل)" "1" "$(jcount "$TMP/body")"
code=$(rest POST /cloud_objects "$TOKEN_A" "$(printf '{"id":"f%s","object_key":"rls/deny-%s","name":"x","size":1,"mime_type":"text/plain","storage_path":"f%s"}' "$UID_A" "$UID_A" "$UID_A")")
expect "قارئ: لا ينشئ عنصرًا (بلا رفع) = 403" "403" "$code"
code=$(rest PATCH "/cloud_objects?object_key=eq.rls/obj-$UID_B" "$TOKEN_A" '{"name":"مُعدَّل illegally"}')
expect "قارئ: تحديث عنصر محجوب = 204 (ترشيح صامت)" "204" "$code"
rest GET "/cloud_objects?object_key=eq.rls/obj-$UID_B" "$TOKEN_A" >/dev/null
expect "قارئ: العنصر ما زال موجودًا" "1" "$(jcount "$TMP/body")"
expect "قارئ: لم يُطبَّق التحديث" "obj" "$(jget "$TMP/body" "0.name")"
code=$(rest DELETE "/cloud_objects?object_key=eq.rls/obj-$UID_B" "$TOKEN_A" "")
expect "قارئ: حذف عنصر محجوب = 204 (ترشيح صامت)" "204" "$code"
rest GET "/cloud_objects?object_key=eq.rls/obj-$UID_B" "$TOKEN_B" >/dev/null
expect "قارئ: العنصر باقٍ (لم يُحذف)" "1" "$(jcount "$TMP/body")"
code=$(rest DELETE "/cloud_objects?object_key=eq.rls/obj-$UID_B" "$TOKEN_B" "")
expect "كامل: يحذف العنصر = 204" "204" "$code"

echo "-- 4) cloud_manifests --"
STAMP="$(date +%s)$$"
DIGEST_RUN=$(printf '%s' "$STAMP" | sha256sum | cut -c1-17)
GEN_RUN="$STAMP"
MANIFEST_ID="m$(printf '%016d' "$GEN_RUN")_${DIGEST_RUN}"
code=$(rest POST /cloud_manifests "$TOKEN_B" "$(printf '{"id":"%s","generation":%s,"payload":{"k":1},"size_bytes":10}' "$MANIFEST_ID" "$GEN_RUN")")
expect "كامل: ينشئ بيانًا = 201" "201" "$code"
code=$(rest POST /cloud_manifests "$TOKEN_B" "$(printf '{"id":"%s","generation":%s,"payload":{"k":1},"size_bytes":10}' "$MANIFEST_ID" "$GEN_RUN")")
expect "كامل: بيان مكرّر = 409 (تعارض تفرّد)" "409" "$code"
code=$(rest POST /cloud_manifests "$TOKEN_A" "$(printf '{"id":"m%s","generation":%s,"payload":{"k":2},"size_bytes":10}' "$DIGEST_RUN" "$GEN_RUN")")
expect "قارئ: لا ينشئ بيانًا = 403" "403" "$code"
code=$(rest GET /cloud_manifests "$TOKEN_A" "")
expect "قارئ: يقرأ البيان (صلاحية تنزيل) = 200" "200" "$code"
code=$(rest GET /rpc/latest_manifest "$TOKEN_A" "")
expect "آخر بيان عبر RPC = 200" "200" "$code"

echo "-- 5) المستخدم بلا أي صلاحية --"
code=$(rest GET /cloud_objects "$TOKEN_D" "")
expect "بلا صلاحية: لا يرى عناصر" "0" "$(jcount "$TMP/body")"
code=$(rest POST /cloud_objects "$TOKEN_D" "$(printf '{"id":"f%s","object_key":"rls/deny2-%s","name":"x","size":1,"mime_type":"text/plain","storage_path":"f%s"}' "$UID_D" "$UID_D" "$UID_D")")
expect "بلا صلاحية: لا ينشئ عنصرًا = 403" "403" "$code"
code=$(storage_list "$TOKEN_D")
expect "بلا صلاحية: سرد الحاوية = 200" "200" "$code"
expect "بلا صلاحية: قائمة الحاوية فارغة" "0" "$(jcount "$TMP/body")"
code=$(storage_list "$TOKEN_A")
expect "قارئ: يسرد ملفات الحاوية = 200" "200" "$code"

echo "-- 6) الحساب الموقوف --"
rest_admin POST /rpc/admin_set_active "$(printf '{"target":"%s","active":false}' "$UID_A")" >/dev/null
code=$(rest GET /cloud_objects "$TOKEN_A" "")
expect "موقوف: لا يرى عناصر" "0" "$(jcount "$TMP/body")"
code=$(rest POST /cloud_objects "$TOKEN_A" "$(printf '{"id":"f%s","object_key":"rls/deny3-%s","name":"x","size":1,"mime_type":"text/plain","storage_path":"f%s"}' "$UID_A" "$UID_A" "$UID_A")")
expect "موقوف: لا ينشئ عنصرًا = 403" "403" "$code"
rest_admin POST /rpc/admin_set_active "$(printf '{"target":"%s","active":true}' "$UID_A")" >/dev/null
code=$(rest GET /cloud_objects "$TOKEN_A" "")
expect "بعد التفعيل: يرى عناصر القائمة" "200" "$code"

echo "-- 7) المشرف --"
code=$(rest GET /profiles "$TOKEN_C" "")
expect "مشرف: يرى كل الصفوف" "200" "$code"
count=$(jcount "$TMP/body")
if [ "$count" -ge 4 ]; then ok "مشرف: عدد الصفوف ≥ 4 (الحالي $count)"; else bad "مشرف: عدد الصفوف ≥ 4" "الحالي $count"; fi
code=$(rest PATCH "/profiles?id=eq.$UID_B" "$TOKEN_C" '{"display_name":"اسم من المشرف"}')
expect "مشرف: يعدّل صفَّ غيره = 204" "204" "$code"
code=$(rest GET "/profiles?id=eq.$UID_B" "$TOKEN_A" "")
expect "قارئ: لا يرى صفَّ غيره بعد التعديل" "0" "$(jcount "$TMP/body")"

echo "-- 8) service_role يتجاوز RLS --"
code=$(rest_admin GET /profiles "")
expect "service_role: يقرأ كل profiles = 200" "200" "$code"
count=$(jcount "$TMP/body")
if [ "$count" -ge 4 ]; then ok "service_role: كل الصفوف (الحالي $count)"; else bad "service_role: كل الصفوف" "الحالي $count"; fi

echo
echo "النتيجة: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
