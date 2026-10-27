-- =====================================================================
-- Migration 5 — face template v2 upgrade path
--
-- v1 templates (geometry only) accepted every frontal face, so the app
-- now stores v2 templates that add an appearance signature. v1 rows must
-- be re-enrolled once: this migration lets save_face_template overwrite
-- a v1 template exactly once, while still refusing to overwrite a v2.
--
-- Re-runnable. Paste the whole file into the Supabase SQL Editor and Run.
-- =====================================================================

begin;

create or replace function public.save_face_template(p_staff_id uuid, p_template text)
returns jsonb
language plpgsql security definer set search_path = public as $$
begin
  if coalesce(btrim(p_template), '') = '' or length(p_template) > 20000 then
    return jsonb_build_object('ok', false, 'error', 'Invalid face template.');
  end if;
  if not exists (select 1 from public.staff where id = p_staff_id) then
    return jsonb_build_object('ok', false, 'error', 'Staff not found.');
  end if;
  -- A v2 template (contains the "app" signature) can never be overwritten.
  -- A v1 template or NULL is replaceable, which is the one-time upgrade path.
  if exists (select 1 from public.staff
              where id = p_staff_id and face_template like '%"app"%') then
    return jsonb_build_object('ok', false, 'error', 'Face already registered for this user.');
  end if;
  update public.staff set face_template = btrim(p_template) where id = p_staff_id;
  return jsonb_build_object('ok', true);
end;
$$;

grant execute on function public.save_face_template(uuid, text) to anon, authenticated;

commit;

-- ---------------------------------------------------------------------
-- VERIFICATION
-- ---------------------------------------------------------------------
select routine_name from information_schema.routines
 where routine_schema = 'public' and routine_name = 'save_face_template';

-- Staff still on a v1 template (is_v2 = false) will be asked by the app
-- to register their face once more after the next APK install:
select id, full_name, coalesce(face_template like '%"app"%', false) as is_v2
  from public.staff
 where face_template is not null
 order by full_name;
