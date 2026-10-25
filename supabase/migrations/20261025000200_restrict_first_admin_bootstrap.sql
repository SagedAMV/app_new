-- إغلاق صلاحيات RPC التأسيسية العامة في البيئات التي طبّقت النسخة السابقة.
-- يجب تشغيل هذا الترحيل بعد 20261025000100_bootstrap_first_admin.sql.
revoke all privileges on function public.bootstrap_first_admin(text, text) from public, anon, authenticated;
grant execute on function public.bootstrap_first_admin(text, text) to service_role;