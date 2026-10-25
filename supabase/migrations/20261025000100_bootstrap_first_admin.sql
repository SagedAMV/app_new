-- =====================================================================
--  UniHub — تمهيد أول حساب مشرف (Bootstrap First Admin)
--  التاريخ: 2026-10-25
--
--  السبب الجذري الذي تعالجه هذه الترحيلة (موثَّق بالكامل في مراجعة.md
--  خارج هذا المستودع): بعد ترحيل الخلفية من Appwrite إلى مشروع Supabase
--  جديد بدأ **فارغًا تمامًا** (0 مستخدم)، لا توجد أي طريقة لإنشاء أول
--  حساب مشرف من داخل التطبيق: `admin_create_user` يتطلّب مشرفًا موجودًا
--  مسبقًا (`public.is_admin()` أو `service_role`) — وهذا تناقض الدجاجة
--  والبيضة. التطبيق لا يملك أي شاشة تسجيل ذاتي (Sign Up) أصلًا، والحل
--  الوحيد الموثَّق سابقًا كان يدويًا بالكامل خارج التطبيق (SQL مباشر
--  بمفتاح `service_role` في لوحة Supabase) — وهذا غير عملي لمستخدم
--  التطبيق العادي، ويفسّر «لا أستطيع تسجيل الدخول إطلاقًا».
--
--  الإصلاح: دالة واحدة، آمنة بشرط صارم غير قابل للالتفاف منطقيًا:
--  تعمل **فقط** إن لم يوجد أي مشرف فعّال في `public.profiles` حاليًا.
--  بمجرد وجود مشرف واحد نشِط، تُرفض كل محاولة لاحقة نهائيًا (403) —
--  فلا تتحوّل إلى ثغرة تسجيل ذاتي دائمة، بل نافذة تمهيد لمرة واحدة فقط،
--  تمامًا كحالة «مشروع جديد بلا أي مستخدم بعد».
--
--  يُستدعى بمفتاح النشر العام فقط (`apikey`) بلا أي رمز مستخدم (anon) —
--  وهذا مقصود: هذه هي اللحظة الوحيدة التي لا يوجد فيها أي مستخدم مسجَّل
--  بعد لتسجيل الدخول به أصلًا.
-- =====================================================================

create or replace function public.bootstrap_first_admin(target_email text, target_password text)
returns uuid
language plpgsql
security definer
set search_path TO 'public'
as $function$
declare
    new_id uuid;
    normalized_email text := lower(btrim(target_email));
begin
    -- الحارس الحاسم: يرفض نهائيًا إن وُجد مشرف نشِط واحد على الأقل.
    -- لا يعتمد على "عدد المستخدمين" (قد يكون هناك مستخدمون عاديون بلا
    -- مشرف بسبب خلل آخر) بل تحديدًا على "عدم وجود من يستطيع الإدارة".
    if exists (select 1 from public.profiles p where p.is_admin and p.is_active) then
        raise exception 'already_initialized' using errcode = '42501';
    end if;

    if target_email is null or target_email !~* '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$'
       or char_length(target_email) > 320 then
        raise exception 'invalid_email' using errcode = '22023';
    end if;
    if target_password is null or char_length(target_password) < 10 or char_length(target_password) > 256
       or btrim(target_password) = '' then
        raise exception 'invalid_password' using errcode = '22023';
    end if;
    if exists (select 1 from auth.users u where lower(u.email) = normalized_email) then
        raise exception 'conflict' using errcode = '23505';
    end if;

    -- نفس نمط إدراج auth.users/auth.identities المُثبَت حيًّا في admin_create_user
    -- (نفس أعمدة GoTrue v2.197 المطلوبة؛ راجع docs/supabase-setup.md §3.1).
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

    -- المشغّل on_auth_user_created يُنشئ صفّ profiles تلقائيًا؛ هنا نرفعه لمشرف
    -- كامل الصلاحيات. إن لم يُنشأ الصفّ (سباق نادر) نُنشئه صراحةً بدلًا منه.
    update public.profiles p
       set is_admin = true, is_active = true,
           can_download = true, can_upload = true, can_modify = true,
           updated_at = now()
     where p.id = new_id;
    if not found then
        insert into public.profiles (id, email, display_name, is_admin, is_active,
                                      can_download, can_upload, can_modify)
        values (new_id, normalized_email, split_part(normalized_email, '@', 1), true, true,
                true, true, true);
    end if;

    return new_id;
end;
$function$;

-- يُستدعى بلا جلسة مطلقًا (قبل وجود أي مستخدم) ⇒ لا بد من صلاحية anon،
-- إضافة إلى authenticated لحالة استدعائه بالخطأ من جلسة قائمة (سيُرفض
-- داخليًا بـ already_initialized على أي حال طالما يوجد مشرف).
grant execute on function public.bootstrap_first_admin(text, text) to anon, authenticated;
