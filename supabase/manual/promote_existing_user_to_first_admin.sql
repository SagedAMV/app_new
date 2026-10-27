-- UniHub: promote a specifically identified existing Auth user to the first admin.
-- Run manually in Supabase SQL Editor as the project owner. Replace the placeholder.
-- This script does NOT create users, reset passwords, or expose service_role to Android.
-- It refuses to proceed if an active admin already exists and updates only the exact email.
DO $$
DECLARE
    target_email text := lower(btrim('REPLACE_WITH_THE_EXISTING_AUTH_EMAIL'));
    target_id uuid;
BEGIN
    IF target_email = 'replace_with_the_existing_auth_email' OR target_email = '' THEN
        RAISE EXCEPTION 'Replace the email placeholder before running this script';
    END IF;

    IF EXISTS (SELECT 1 FROM public.profiles WHERE is_admin AND is_active) THEN
        RAISE EXCEPTION 'An active admin already exists; no changes made';
    END IF;

    SELECT id INTO target_id
      FROM auth.users
     WHERE lower(email) = target_email
     LIMIT 1;

    IF target_id IS NULL THEN
        RAISE EXCEPTION 'No Auth user found for the supplied email; no changes made';
    END IF;

    UPDATE public.profiles
       SET is_admin = true,
           is_active = true,
           can_download = true,
           can_upload = true,
           can_modify = true,
           updated_at = now()
     WHERE id = target_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Auth user exists but profile row is missing; no changes made';
    END IF;
END $$;

-- Verify result without exposing password or tokens:
SELECT id, email, is_admin, is_active, can_download, can_upload, can_modify
FROM public.profiles
WHERE lower(email) = lower('REPLACE_WITH_THE_EXISTING_AUTH_EMAIL');
