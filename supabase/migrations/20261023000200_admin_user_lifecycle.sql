-- =====================================================================
--  UniHub — دورة حياة المستخدم الإداري (Supabase)
--  التاريخ: 2026-10-23
--
--  لماذا دوال في القاعدة بدل مفتاح إداري في التطبيق:
--  التطبيق يتكلّم مع Supabase برمز **مستخدم** فقط. لا يملك مفتاح
--  `service_role` ولا ningún مفتاح إداري (طبيعة تطبيق.md: لا secrets
--  عالية الصلاحية داخل التطبيق ولا في ملفات المشروع).
--  كل عملية إدارية (إنشاء/حذف/إيقاف/صلاحيات/كلمة مرور/إلغاء جلسات)
--  تُنفَّذ داخل القاعدة، وتتحقّق من `is_admin()` أو `service_role`.
--
--  طُبِّقت فعليًا (migrations: admin_functions_allow_service_role،
--  admin_user_lifecycle v2..v5) ومُتحقَّق منها بـ:
--    supabase/tests/admin_lifecycle_test.sh  (21/21)
-- =====================================================================

-- ─────────────────────────── قائمة المستخدمين ───────────────────────────
create or replace function public.admin_list_users()
returns table(id uuid, email text, display_name text, is_admin boolean, is_active boolean,
              can_download boolean, can_upload boolean, can_modify boolean,
              created_at timestamp with time zone, updated_at timestamp with time zone)
language plpgsql
stable security definer
set search_path TO 'public'
as $function$
begin
    if not (public.is_admin() or auth.role() = 'service_role') then
        raise exception 'forbidden' using errcode = '42501';
    end if;
    return query
        select p.id, p.email, p.display_name, p.is_admin, p.is_active,
               p.can_download, p.can_upload, p.can_modify, p.created_at, p.updated_at
        from public.profiles p order by p.email;
end;
$function$;

-- ─────────────────────────── ضبط الصلاحيات ───────────────────────────
create or replace function public.admin_set_permissions(target uuid, download boolean, upload boolean, modify boolean)
returns void
language plpgsql
security definer
set search_path TO 'public'
as $function$
begin
    if not (public.is_admin() or auth.role() = 'service_role') then
        raise exception 'forbidden' using errcode = '42501';
    end if;
    if (modify and not (upload and download)) or (upload and not download) then
        raise exception 'invalid_permissions' using errcode = '22023'; end if;
    if auth.uid() is not null and target = auth.uid() then
        raise exception 'protected_admin' using errcode = '42501'; end if;
    if exists (select 1 from public.profiles p where p.id = target and p.is_admin) then
        raise exception 'protected_admin' using errcode = '42501'; end if;
    update public.profiles p set can_download = download, can_upload = upload, can_modify = modify, updated_at = now()
     where p.id = target;
    if not found then raise exception 'not_found' using errcode = 'P0002'; end if;
end;
$function$;

-- ─────────────────────────── التفعيل / الإيقاف ───────────────────────────
create or replace function public.admin_set_active(target uuid, active boolean)
returns void
language plpgsql
security definer
set search_path TO 'public'
as $function$
begin
    if not (public.is_admin() or auth.role() = 'service_role') then
        raise exception 'forbidden' using errcode = '42501'; end if;
    if auth.uid() is not null and target = auth.uid() then
        raise exception 'protected_admin' using errcode = '42501'; end if;
    if exists (select 1 from public.profiles p where p.id = target and p.is_admin) then
        raise exception 'protected_admin' using errcode = '42501'; end if;
    update public.profiles p set is_active = active, updated_at = now() where p.id = target;
    if not found then raise exception 'not_found' using errcode = 'P0002'; end if;
    if not active then delete from auth.sessions s where s.user_id = target; end if;
end;
$function$;

-- ─────────────────────────── إلغاء الجلسات ───────────────────────────
create or replace function public.admin_revoke_sessions(target uuid)
returns void
language plpgsql
security definer
set search_path TO 'public'
as $function$
begin
    if not (public.is_admin() or auth.role() = 'service_role') then
        raise exception 'forbidden' using errcode = '42501'; end if;
    delete from auth.sessions s where s.user_id = target;
end;
$function$;

-- ─────────────────────────── إنشاء مستخدم ───────────────────────────
-- إدراج مباشر في auth.users + auth.identities (مطلوب في GoTrue v2.197).
-- ملاحظة: `auth.identities.email` عمود مولَّد — لا يُدرَج أبدًا.
create or replace function public.admin_create_user(target_email text, target_password text,
                                                    download boolean, upload boolean, modify boolean)
returns uuid
language plpgsql
security definer
set search_path TO 'public'
as $function$
declare
    new_id uuid;
    normalized_email text := lower(btrim(target_email));
begin
    if not (public.is_admin() or auth.role() = 'service_role') then
        raise exception 'forbidden' using errcode = '42501';
    end if;
    if target_email is null or target_email !~* '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$'
       or char_length(target_email) > 320 then
        raise exception 'invalid_email' using errcode = '22023';
    end if;
    if target_password is null or char_length(target_password) < 10 or char_length(target_password) > 256
       or btrim(target_password) = '' then
        raise exception 'invalid_password' using errcode = '22023';
    end if;
    if (modify and not (upload and download)) or (upload and not download) then
        raise exception 'invalid_permissions' using errcode = '22023';
    end if;
    if exists (select 1 from auth.users u where lower(u.email) = normalized_email) then
        raise exception 'conflict' using errcode = '23505';
    end if;

    new_id := gen_random_uuid();
    insert into auth.users (
        id, email, encrypted_password, email_confirmed_at,
        created_at, updated_at, raw_user_meta_data, raw_app_meta_data,
        aud, role, is_anonymous, is_sso_user, instance_id,
        confirmation_token, email_change, email_change_token_current, email_change_token_new,
        phone_change, phone_change_token, reauthentication_token, recovery_token,
        email_change_confirm_status)
    values (
        new_id, normalized_email,
        extensions.crypt(target_password, extensions.gen_salt('bf', 10)),
        now(), now(), now(),
        jsonb_build_object('email_verified', true),
        jsonb_build_object('provider', 'email', 'providers', jsonb_build_array('email')),
        'authenticated', 'authenticated', false, false,
        '00000000-0000-0000-0000-000000000000'::uuid,
        '', '', '', '', '', '', '', '', 0);

    insert into auth.identities (provider_id, user_id, identity_data, provider,
                                 last_sign_in_at, created_at, updated_at)
    values (new_id::text, new_id,
            jsonb_build_object('sub', new_id::text, 'email', normalized_email,
                               'email_verified', false, 'phone_verified', false),
            'email', now(), now(), now());

    update public.profiles p
       set can_download = download, can_upload = upload, can_modify = modify, updated_at = now()
     where p.id = new_id;
    if not found then
        insert into public.profiles (id, email, display_name, can_download, can_upload, can_modify)
        values (new_id, normalized_email, split_part(normalized_email, '@', 1),
                download, upload, modify);
    end if;
    return new_id;
end;
$function$;

-- ─────────────────────────── حذف مستخدم ───────────────────────────
create or replace function public.admin_delete_user(target uuid)
returns void
language plpgsql
security definer
set search_path TO 'public'
as $function$
begin
    if not (public.is_admin() or auth.role() = 'service_role') then
        raise exception 'forbidden' using errcode = '42501';
    end if;
    if auth.uid() is not null and target = auth.uid() then
        raise exception 'protected_admin' using errcode = '42501';
    end if;
    if exists (select 1 from public.profiles p where p.id = target and p.is_admin) then
        raise exception 'protected_admin' using errcode = '42501';
    end if;
    if not exists (select 1 from auth.users u where u.id = target) then
        raise exception 'not_found' using errcode = 'P0002';
    end if;
    -- حذف المستخدم يتتالى إلى إعداداته وحساباته وبياناته (قيود CASCADE في auth).
    -- ملفات السحابة تبقى على القرص (لأن owner_id يصبح NULL دالّة الشُّهود).
    delete from auth.users where id = target;
end;
$function$;

-- ─────────────────────────── ضبط كلمة مرور ───────────────────────────
create or replace function public.admin_set_password(target uuid, new_password text)
returns void
language plpgsql
security definer
set search_path TO 'public'
as $function$
begin
    if not (public.is_admin() or auth.role() = 'service_role') then
        raise exception 'forbidden' using errcode = '42501';
    end if;
    if new_password is null or char_length(new_password) < 10 or char_length(new_password) > 256
       or btrim(new_password) = '' then
        raise exception 'invalid_password' using errcode = '22023';
    end if;
    if not exists (select 1 from auth.users u where u.id = target) then
        raise exception 'not_found' using errcode = 'P0002';
    end if;
    update auth.users
       set encrypted_password = extensions.crypt(new_password, extensions.gen_salt('bf', 10)),
           updated_at = now()
     where id = target;
    delete from auth.sessions where user_id = target;
end;
$function$;
