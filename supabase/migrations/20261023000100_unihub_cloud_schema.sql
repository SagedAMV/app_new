-- =====================================================================
--  UniHub — مخطط السحابة على Supabase
--  التاريخ: 2026-10-23   |  المشروع: yjvrkxabhlygdmtoujge (us-east-1)
--
--  هذا الملف هو نسخة «المصدر» لما هو مطبَّق فعليًا على المشروع.
--  طُبِّق فعليًا عبر Supabase (migration: unihub_cloud_schema) بتاريخ
--  2026-10-23 19:45:26 UTC، ومحتواه متحقَّق منه بـ:
--    - supabase/tests/rls_smoke_test.sh   (29/29)
--    - supabase/tests/cloud_files_test.sh (14/14)
--
--  القاعدة الحاكمة (طبيعة تطبيق.md): الوصول يُفرض في القاعدة (RLS)،
--  لا في الواجهة فقط. كل دالة مساعدة هنا `SECURITY DEFINER` مع
--  `search_path = public` كي لا تُختطَأ بمسار بحث من المستخدم.
--
--  ⚠ لا تضع أي مفتاح إداري هنا. هذا الملف يُطبَّق بحساب إداري خارج
--    التطبيق، والتطبيق نفسه يتكلّم عبر `apikey` (مفتاح نشر) + رمز مستخدم.
-- =====================================================================

-- ───────────────────────────── 1) جداول البيانات ─────────────────────────────

-- الملف/العنصر البعيد كما كان في Appwrite: مفتاح منطقي + اسم + حجم + نوع.
create table if not exists public.profiles (
    id            uuid primary key references auth.users (id) on delete cascade,
    email         text        not null,
    display_name  text        not null default '',
    is_admin      boolean     not null default false,
    is_active     boolean     not null default true,
    can_download  boolean     not null default false,
    can_upload    boolean     not null default false,
    can_modify    boolean     not null default false,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);

-- سجل العنصر البعيد. `id` = معرّف الملف المشتق (f + sha256 بادئة 35)
-- ⇒ مفتاح الحاوية ASCII حتمي (Storage يرفض المفاتيح غير اللاتينية).
create table if not exists public.cloud_objects (
    id            text primary key,
    object_key    text        not null unique,
    name          text        not null,
    size          bigint      not null default 0,
    mime_type     text        not null default 'application/octet-stream',
    storage_path  text        not null,
    version       text        not null default '',
    sha256        text        not null default '',
    owner_id      uuid        references auth.users (id) on delete set null,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);

-- بيان المزامنة: كل جيل = لقطة كاملة. `id` = m<generation:016d>_<sha17>
-- ⇒ تعارض التفرّد (23505) هو ما يقابل 409 «compare-and-create» في Appwrite.
create table if not exists public.cloud_manifests (
    id           text primary key,
    generation   bigint      not null,
    payload      jsonb       not null,
    size_bytes   bigint      not null default 0,
    owner_id     uuid        references auth.users (id) on delete set null,
    created_at   timestamptz not null default now()
);

create index if not exists cloud_objects_owner_idx   on public.cloud_objects (owner_id);
create index if not exists cloud_manifests_gen_idx   on public.cloud_manifests (generation desc);
create index if not exists profiles_email_idx         on public.profiles (email);

-- ───────────────────────────── 2) دوال الصلاحيات ─────────────────────────────

create or replace function public.current_user_id()
returns uuid
language sql
stable security definer
set search_path TO 'public'
as $function$ select auth.uid(); $function$;

create or replace function public.is_admin()
returns boolean
language sql
stable security definer
set search_path TO 'public'
as $function$
    select coalesce((select p.is_admin and p.is_active from public.profiles p where p.id = auth.uid()), false); $function$;

-- تسلسل الصلاحيات: تعديل⇒رفع⇒قراءة. مطبَّق هنا وفي التطبيق وفي
-- `admin_set_permissions` (المستوى الأدنى لا يُمنح بلا ما فوقه).
create or replace function public.effective_can_download()
returns boolean
language sql
stable security definer
set search_path TO 'public'
as $function$
    select coalesce((select p.is_active and (p.is_admin or p.can_download) from public.profiles p where p.id = auth.uid()), false); $function$;

create or replace function public.effective_can_upload()
returns boolean
language sql
stable security definer
set search_path TO 'public'
as $function$
    select coalesce((select p.is_active and (p.is_admin or (p.can_upload and p.can_download)) from public.profiles p where p.id = auth.uid()), false); $function$;

create or replace function public.effective_can_modify()
returns boolean
language sql
stable security definer
set search_path TO 'public'
as $function$
    select coalesce((select p.is_active and (p.is_admin or (p.can_modify and p.can_upload and p.can_download)) from public.profiles p where p.id = auth.uid()), false); $function$;

-- آخر بيان مزامنة (يُقرأ عبر RPC).
create or replace function public.latest_manifest()
returns table(id text, generation bigint, payload jsonb, size_bytes bigint, created_at timestamp with time zone)
language sql
stable security definer
set search_path TO 'public'
as $function$
    select m.id, m.generation, m.payload, m.size_bytes, m.created_at
    from public.cloud_manifests m order by m.generation desc limit 1;
$function$;

-- ───────────────────────────── 3) مشغّلات الحساب ─────────────────────────────

-- إنشاء صف ملف شخصي تلقائيًا عند إنشاء المستخدم في GoTrue.
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path TO 'public'
as $function$
begin
    insert into public.profiles (id, email, display_name)
    values (new.id, new.email,
            coalesce(new.raw_user_meta_data ->> 'name', split_part(coalesce(new.email, ''), '@', 1)))
    on conflict (id) do nothing;
    return new;
end;
$function$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
    after insert on auth.users
    for each row execute function public.handle_new_user();

-- المستخدم لا يعدّل صلاحياته بنفسه؛ المشرف فقط (أو service_role).
create or replace function public.guard_profile_privileges()
returns trigger
language plpgsql
security definer
set search_path TO 'public'
as $function$
begin
    if auth.uid() is null and auth.role() <> 'service_role' then
        raise exception 'unauthorized' using errcode = '42501';
    end if;
    if not (public.is_admin() or auth.role() = 'service_role') then
        if new.id is distinct from old.id
           or new.is_admin is distinct from old.is_admin
           or new.is_active is distinct from old.is_active
           or new.can_download is distinct from old.can_download
           or new.can_upload is distinct from old.can_upload
           or new.can_modify is distinct from old.can_modify then
            raise exception 'forbidden' using errcode = '42501';
        end if;
    end if;
    new.updated_at = now();
    return new;
end;
$function$;

drop trigger if exists profiles_guard_privileges on public.profiles;
create trigger profiles_guard_privileges
    before update on public.profiles
    for each row execute function public.guard_profile_privileges();

-- ───────────────────────────── 4) RLS على جداول التطبيق ─────────────────────────────

alter table public.profiles       enable row level security;
alter table public.cloud_objects  enable row level security;
alter table public.cloud_manifests enable row level security;

-- profiles: صاحب الحساب يرى صفه ويحدّث ما لا يمسّ الصلاحيات (المشغّل يمنع الأعمدة الحساسة).
drop policy if exists profiles_select_own   on public.profiles;
create policy profiles_select_own on public.profiles
    for select to public
    using (id = auth.uid());

drop policy if exists profiles_update_own   on public.profiles;
create policy profiles_update_own on public.profiles
    for update to public
    using (id = auth.uid())
    with check (id = auth.uid());

drop policy if exists profiles_select_admin on public.profiles;
create policy profiles_select_admin on public.profiles
    for select to public
    using (is_admin());

drop policy if exists profiles_admin_all on public.profiles;
create policy profiles_admin_all on public.profiles
    for all to public
    using (is_admin())
    with check (is_admin());

-- cloud_objects: قراءة ⇒ صلاحية تنزيل، كتابة ⇒ رفع، تعديل/حذف ⇒ تعديل.
drop policy if exists cloud_objects_read   on public.cloud_objects;
create policy cloud_objects_read on public.cloud_objects
    for select to public
    using (effective_can_download());

drop policy if exists cloud_objects_create on public.cloud_objects;
create policy cloud_objects_create on public.cloud_objects
    for insert to public
    with check (effective_can_upload());

drop policy if exists cloud_objects_update on public.cloud_objects;
create policy cloud_objects_update on public.cloud_objects
    for update to public
    using (effective_can_modify())
    with check (effective_can_modify());

drop policy if exists cloud_objects_delete on public.cloud_objects;
create policy cloud_objects_delete on public.cloud_objects
    for delete to public
    using (effective_can_modify());

-- cloud_manifests: البيان نفسه يقرأه من له تنزيل، ويكتبه من له تعديل.
drop policy if exists cloud_manifests_read   on public.cloud_manifests;
create policy cloud_manifests_read on public.cloud_manifests
    for select to public
    using (effective_can_download());

drop policy if exists cloud_manifests_create on public.cloud_manifests;
create policy cloud_manifests_create on public.cloud_manifests
    for insert to public
    with check (effective_can_upload() and effective_can_modify());

drop policy if exists cloud_manifests_update on public.cloud_manifests;
create policy cloud_manifests_update on public.cloud_manifests
    for update to public
    using (effective_can_modify())
    with check (effective_can_modify());

drop policy if exists cloud_manifests_delete on public.cloud_manifests;
create policy cloud_manifests_delete on public.cloud_manifests
    for delete to public
    using (effective_can_modify());

-- ───────────────────────────── 5) الحاوية وسياسات storage ─────────────────────────────

insert into storage.buckets (id, name, public, file_size_limit)
values ('unihub-files', 'unihub-files', false, 50000000)
on conflict (id) do update
    set public = excluded.public,
        file_size_limit = excluded.file_size_limit;

drop policy if exists storage_objects_read   on storage.objects;
create policy storage_objects_read on storage.objects
    for select to public
    using (bucket_id = 'unihub-files' and effective_can_download());

drop policy if exists storage_objects_create on storage.objects;
create policy storage_objects_create on storage.objects
    for insert to public
    with check (bucket_id = 'unihub-files' and effective_can_upload());

drop policy if exists storage_objects_update on storage.objects;
create policy storage_objects_update on storage.objects
    for update to public
    using (bucket_id = 'unihub-files' and effective_can_modify())
    with check (bucket_id = 'unihub-files' and effective_can_modify());

drop policy if exists storage_objects_delete on storage.objects;
create policy storage_objects_delete on storage.objects
    for delete to public
    using (bucket_id = 'unihub-files' and effective_can_modify());
