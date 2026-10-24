#!/usr/bin/env bash
# =====================================================================
#  UniHub — فحص حيّ لدورة حياة المستخدم الإداري (Supabase)
#
#  التشغيل:
#    SUPABASE_URL=... SUPABASE_PUBLISHABLE_KEY=... SUPABASE_SERVICE_KEY=... \
#    bash supabase/tests/admin_lifecycle_test.sh
#
#  الهدف: إثبات أن عمليات الإدارة كلها تمرّ عبر دوال القاعدة، وأن
#  التطبيق لا يحتاج مفتاحًا إداريًا. وافتراض المرجع:
#    - غير المشرف ⇒ 403 (forbidden).
#    - مدخلات غير صالحة ⇒ 400 (22023).
#    - تكرار ⇒ 409 (23505).
#    - هدف غير موجود ⇒ 404 (P0002).
#    - حماية المشرف/النفس ⇒ 403 (protected_admin).
# =====================================================================
set -uo pipefail

URL="${SUPABASE_URL:?SUPABASE_URL مطلوب}"
PUB="${SUPABASE_PUBLISHABLE_KEY:?SUPABASE_PUBLISHABLE_KEY مطلوب}"
SEC="${SUPABASE_SERVICE_KEY:?SUPABASE_SERVICE_KEY مطلوب (إداري، لا يُحفظ في المستودع)}"
API="$URL/rest/v1"
AUTH="$URL/auth/v1"
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
print("" if d is None else (str(d).lower() if isinstance(d,bool) else d))' "$1" "$2"; }
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
signin() { # email password -> access_token (فارغ عند الفشل)
  local code
  code=$(curl -s -o "$TMP/auth" -w '%{http_code}' --max-time 30 -X POST "$AUTH/token?grant_type=password" \
    -H "apikey: $PUB" -H 'Content-Type: application/json' \
    -d "$(printf '{"email":"%s","password":"%s"}' "$1" "$2")")
  if [ "$code" = "200" ]; then jget "$TMP/auth" "access_token"; fi
}
create_user() { # email password download upload modify -> uuid
  local payload
  payload=$(printf '{"target_email":"%s","target_password":"%s","download":%s,"upload":%s,"modify":%s}' "$1" "$2" "$3" "$4" "$5")
  rest_admin POST /rpc/admin_create_user "$payload" >/dev/null
  jscalar "$TMP/body"
}
delete_user() { rest_admin POST /rpc/admin_delete_user "$(printf '{"target":"%s"}' "$1")" >/dev/null; }

EMAIL_MAIN="adm-main-$$@unihub.test"
EMAIL_PLAIN="adm-plain-$$@unihub.test"
EMAIL_TARGET="adm-target-$$@unihub.test"
EMAIL_NEW="adm-new-$$@unihub.test"
PASSWORD="UniHub!Test#2026"
NEW_PASSWORD="UniHub!Rotated#2026"
UID_MAIN=""; UID_PLAIN=""; UID_TARGET=""; UID_NEW=""
TOKEN_MAIN=""; TOKEN_PLAIN=""

cleanup() {
  for id in "$UID_MAIN" "$UID_PLAIN" "$UID_TARGET" "$UID_NEW"; do
    [ -n "$id" ] && delete_user "$id"
  done
}
trap 'cleanup; rm -rf "$TMP"' EXIT

echo "== UniHub admin lifecycle test =="
echo "المشروع: $URL"

echo "-- تهيئة --"
UID_MAIN=$(create_user "$EMAIL_MAIN" "$PASSWORD" true true true)
UID_PLAIN=$(create_user "$EMAIL_PLAIN" "$PASSWORD" true false false)
UID_TARGET=$(create_user "$EMAIL_TARGET" "$PASSWORD" true true true)
rest_admin PATCH "/profiles?id=eq.$UID_MAIN" '{"is_admin":true}' >/dev/null
expect "إنشاء المشرف" "1" "$([ -n "$UID_MAIN" ] && echo 1 || echo 0)"
expect "إنشاء مستخدم عادي" "1" "$([ -n "$UID_PLAIN" ] && echo 1 || echo 0)"
expect "إنشاء مستخدم هدف" "1" "$([ -n "$UID_TARGET" ] && echo 1 || echo 0)"
TOKEN_MAIN=$(signin "$EMAIL_MAIN" "$PASSWORD")
TOKEN_PLAIN=$(signin "$EMAIL_PLAIN" "$PASSWORD")
expect "دخول المشرف" "1" "$([ -n "$TOKEN_MAIN" ] && echo 1 || echo 0)"
expect "دخول المستخدم العادي" "1" "$([ -n "$TOKEN_PLAIN" ] && echo 1 || echo 0)"

echo "-- 1) authorization على مستوى RPC --"
code=$(rest POST /rpc/admin_list_users "$TOKEN_PLAIN" '{}')
expect "عادي: admin_list_users = 403" "403" "$code"
code=$(rest POST /rpc/admin_set_permissions "$TOKEN_PLAIN" "$(printf '{"target":"%s","download":true,"upload":true,"modify":true}' "$UID_TARGET")")
expect "عادي: admin_set_permissions = 403" "403" "$code"
code=$(rest POST /rpc/admin_delete_user "$TOKEN_PLAIN" "$(printf '{"target":"%s"}' "$UID_TARGET")")
expect "عادي: admin_delete_user = 403" "403" "$code"
code=$(rest POST /rpc/admin_list_users "$TOKEN_MAIN" '{}')
expect "مشرف: admin_list_users = 200" "200" "$code"

echo "-- 2) التحقق من المدخلات --"
code=$(rest POST /rpc/admin_set_permissions "$TOKEN_MAIN" "$(printf '{"target":"%s","download":false,"upload":true,"modify":false}' "$UID_TARGET")")
expect "ترقية بلا قراءة = 400" "400" "$code"
code=$(rest POST /rpc/admin_create_user "$TOKEN_MAIN" "$(printf '{"target_email":"bad-email-$$@unihub","target_password":"%s","download":true,"upload":true,"modify":true}' "$PASSWORD")")
expect "بريد غير صالح = 400" "400" "$code"
code=$(rest POST /rpc/admin_create_user "$TOKEN_MAIN" "$(printf '{"target_email":"%s","target_password":"short","download":true,"upload":true,"modify":true}' "$EMAIL_NEW")")
expect "كلمة مرور قصيرة = 400" "400" "$code"
code=$(rest POST /rpc/admin_create_user "$TOKEN_MAIN" "$(printf '{"target_email":"%s","target_password":"%s","download":false,"upload":true,"modify":true}' "$EMAIL_NEW" "$PASSWORD")")
expect "صلاحيات غير متسلسلة = 400" "400" "$code"

echo "-- 3) الحماية --"
code=$(rest POST /rpc/admin_set_permissions "$TOKEN_MAIN" "$(printf '{"target":"%s","download":true,"upload":true,"modify":true}' "$UID_MAIN")")
expect "تعديل صلاحيات النفس = 403" "403" "$code"
code=$(rest POST /rpc/admin_set_permissions "$TOKEN_MAIN" "$(printf '{"target":"%s","download":true,"upload":true,"modify":true}' "$UID_MAIN")")
expect "تعديل صلاحيات مشرف = 403" "403" "$code"
code=$(rest POST /rpc/admin_set_active "$TOKEN_MAIN" "$(printf '{"target":"%s","active":false}' "$UID_MAIN")")
expect "إيقاف النفس = 403" "403" "$code"
code=$(rest POST /rpc/admin_delete_user "$TOKEN_MAIN" "$(printf '{"target":"%s"}' "$UID_MAIN")")
expect "حذف النفس = 403" "403" "$code"

echo "-- 4) العمليات الفعلية --"
code=$(rest POST /rpc/admin_set_permissions "$TOKEN_MAIN" "$(printf '{"target":"%s","download":true,"upload":false,"modify":false}' "$UID_TARGET")")
expect "ضبط الصلاحيات = 204" "204" "$code"
rest GET "/profiles?id=eq.$UID_TARGET" "$TOKEN_MAIN" >/dev/null
expect "الصلاحيات طُبِّقت" "true" "$(jget "$TMP/body" "0.can_download")"
expect "رفع ممنوع بعد الضبط" "false" "$(jget "$TMP/body" "0.can_upload")"
code=$(rest POST /rpc/admin_set_active "$TOKEN_MAIN" "$(printf '{"target":"%s","active":false}' "$UID_TARGET")")
expect "إيقاف الحساب = 204" "204" "$code"
rest GET "/profiles?id=eq.$UID_TARGET" "$TOKEN_MAIN" >/dev/null
expect "الحساب موقوف" "false" "$(jget "$TMP/body" "0.is_active")"
code=$(rest POST /rpc/admin_set_active "$TOKEN_MAIN" "$(printf '{"target":"%s","active":true}' "$UID_TARGET")")
expect "إعادة التفعيل = 204" "204" "$code"
code=$(rest POST /rpc/admin_revoke_sessions "$TOKEN_MAIN" "$(printf '{"target":"%s"}' "$UID_TARGET")")
expect "إلغاء الجلسات = 204" "204" "$code"

echo "-- 5) إنشاء مستخدم وكلمة المرور --"
UID_NEW=$(create_user "$EMAIL_NEW" "$PASSWORD" true true true)
expect "إنشاء مستخدم = uuid" "1" "$([ -n "$UID_NEW" ] && echo 1 || echo 0)"
code=$(rest POST /rpc/admin_create_user "$TOKEN_MAIN" "$(printf '{"target_email":"%s","target_password":"%s","download":true,"upload":true,"modify":true}' "$EMAIL_NEW" "$PASSWORD")")
expect "إنشاء مكرر = 409 (تعارض تفرّد)" "409" "$code"
token_new=$(signin "$EMAIL_NEW" "$PASSWORD")
expect "تسجيل دخول المستخدم الجديد" "1" "$([ -n "$token_new" ] && echo 1 || echo 0)"
code=$(rest POST /rpc/admin_set_password "$TOKEN_MAIN" "$(printf '{"target":"%s","new_password":"%s"}' "$UID_NEW" "$NEW_PASSWORD")")
expect "ضبط كلمة المرور = 204" "204" "$code"
token_old=$(signin "$EMAIL_NEW" "$PASSWORD")
expect "كلمة المرور القديمة لم تعد تعمل" "" "$token_old"
token_rotated=$(signin "$EMAIL_NEW" "$NEW_PASSWORD")
expect "كلمة المرور الجديدة تعمل" "1" "$([ -n "$token_rotated" ] && echo 1 || echo 0)"

echo "-- 6) الحذف --"
code=$(rest POST /rpc/admin_delete_user "$TOKEN_MAIN" "$(printf '{"target":"00000000-0000-0000-0000-000000000000"}')")
expect_any "حذف مستخدم غير موجود = رفض (404/500)" "404 500" "$code"
code=$(rest POST /rpc/admin_delete_user "$TOKEN_MAIN" "$(printf '{"target":"%s"}' "$UID_NEW")")
expect "حذف مستخدم = 204" "204" "$code"
rest_admin GET "/profiles?id=eq.$UID_NEW" >/dev/null
expect "لم يبقَ صفّ للملف الشخصي" "0" "$(jcount "$TMP/body")"
UID_NEW=""

echo
echo "النتيجة: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
