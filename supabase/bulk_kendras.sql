-- =====================================================================
-- Dhan Kharidi Samiksha — BULK KENDRA UPLOAD (migration 8)
-- Run AFTER supabase/shifts_nodal.sql. Single re-runnable transaction.
-- Paste the WHOLE file into the Supabase SQL editor and run it.
--
-- What changes:
--   * New admin RPC admin_bulk_kendras(p_rows jsonb) — the dashboard can
--     now create many kendras from one Excel/CSV upload, exactly like
--     admin_bulk_enroll() does for staff.
--   * Every row is validated on its own: one bad row is reported back
--     with its reason and the rest of the file still imports.
--   * Duplicate kendra names are rejected, because the staff bulk upload
--     matches kendras BY NAME — two kendras with the same name would send
--     staff to whichever one the lookup happens to find first.
-- =====================================================================

begin;

create or replace function public.admin_bulk_kendras(p_rows jsonb)
returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_row   jsonb;
  v_res   jsonb := '[]'::jsonb;
  v_name  text;
  v_lat_t text; v_lon_t text; v_rad_t text; v_ver_t text;
  v_lat   double precision; v_lon double precision;
  v_latn  numeric; v_lonn numeric;
  v_rad   integer; v_ver integer;
  v_id    uuid;
  v_err   text;
begin
  perform public.assert_admin();
  if jsonb_typeof(p_rows) <> 'array' then
    return jsonb_build_object('ok', false, 'error', 'Expected an array of rows.');
  end if;

  for v_row in select * from jsonb_array_elements(p_rows) loop
    v_name  := btrim(coalesce(v_row->>'name',''));
    v_lat_t := btrim(coalesce(v_row->>'latitude',''));
    v_lon_t := btrim(coalesce(v_row->>'longitude',''));
    v_rad_t := btrim(coalesce(v_row->>'radius_m',''));
    v_ver_t := btrim(coalesce(v_row->>'verify_interval_min',''));
    v_id    := null;
    v_err   := null;
    v_lat   := null;
    v_lon   := null;
    v_rad   := null;
    v_ver   := null;

    -- name -----------------------------------------------------------
    if v_name = '' then
      v_err := 'Kendra name is required.';
    elsif exists (select 1 from public.kendras where lower(name) = lower(v_name)) then
      v_err := 'Kendra name already exists: ' || v_name;
    end if;

    -- latitude -------------------------------------------------------
    if v_err is null then
      if v_lat_t = '' then
        v_err := 'Latitude is required.';
      elsif v_lat_t !~ '^[+-]?([0-9]+(\.[0-9]*)?|\.[0-9]+)([eE][+-]?[0-9]+)?$' then
        v_err := 'Latitude is not a number: ' || v_lat_t;
      else
        -- range-check as numeric first: a huge Excel value must fail this
        -- row, not blow up the float cast and abort the whole import.
        v_latn := v_lat_t::numeric;
        if v_latn < -90 or v_latn > 90 then
          v_err := 'Latitude must be between -90 and 90: ' || v_lat_t;
        else
          v_lat := v_latn::double precision;
        end if;
      end if;
    end if;

    -- longitude ------------------------------------------------------
    if v_err is null then
      if v_lon_t = '' then
        v_err := 'Longitude is required.';
      elsif v_lon_t !~ '^[+-]?([0-9]+(\.[0-9]*)?|\.[0-9]+)([eE][+-]?[0-9]+)?$' then
        v_err := 'Longitude is not a number: ' || v_lon_t;
      else
        v_lonn := v_lon_t::numeric;
        if v_lonn < -180 or v_lonn > 180 then
          v_err := 'Longitude must be between -180 and 180: ' || v_lon_t;
        else
          v_lon := v_lonn::double precision;
        end if;
      end if;
    end if;

    -- geofence radius ------------------------------------------------
    if v_err is null then
      if v_rad_t = '' then
        v_rad := 100;
      elsif v_rad_t !~ '^[0-9]{1,4}$' then
        v_err := 'Radius must be a whole number of metres (10 to 5000): ' || v_rad_t;
      else
        v_rad := v_rad_t::integer;
        if v_rad < 10 or v_rad > 5000 then
          v_err := 'Radius must be between 10 and 5000 metres: ' || v_rad_t;
        end if;
      end if;
    end if;

    -- re-verification cadence ----------------------------------------
    if v_err is null then
      if v_ver_t = '' then
        v_ver := 30;
      elsif v_ver_t !~ '^[0-9]{1,3}$' then
        v_err := 'Verify interval must be a whole number of minutes (1 to 240): ' || v_ver_t;
      else
        v_ver := v_ver_t::integer;
        if v_ver < 1 or v_ver > 240 then
          v_err := 'Verify interval must be between 1 and 240 minutes: ' || v_ver_t;
        end if;
      end if;
    end if;

    if v_err is not null then
      v_res := v_res || jsonb_build_object(
        'name', v_name, 'latitude', v_lat_t, 'longitude', v_lon_t,
        'ok', false, 'error', v_err);
      continue;
    end if;

    insert into public.kendras (name, latitude, longitude, radius_m, verify_interval_min)
    values (v_name, v_lat, v_lon, v_rad, v_ver)
    returning id into v_id;

    v_res := v_res || jsonb_build_object(
      'name', v_name, 'latitude', v_lat, 'longitude', v_lon,
      'radius_m', v_rad, 'verify_interval_min', v_ver,
      'ok', true, 'id', v_id);
  end loop;

  return jsonb_build_object('ok', true, 'results', v_res);
end;
$$;

grant execute on function public.admin_bulk_kendras(jsonb) to authenticated;

commit;

-- ---------------------------------------------------------------------
-- VERIFICATION
-- ---------------------------------------------------------------------
-- 1. the function exists, is SECURITY DEFINER and takes one jsonb array.
select routine_name, security_type, data_type as returns,
       pg_get_function_identity_arguments(p.oid) as args
  from information_schema.routines r
  join pg_proc p on p.proname = r.routine_name
  join pg_namespace n on n.oid = p.pronamespace and n.nspname = r.routine_schema
 where r.routine_schema = 'public' and r.routine_name = 'admin_bulk_kendras';

-- 2. the dashboard role may call it.
select grantee, privilege_type
  from information_schema.role_routine_grants
 where routine_schema = 'public' and routine_name = 'admin_bulk_kendras';

-- 3. should return 0 rows: no two existing kendras share a name. A
--    duplicate already in the table would make the staff bulk upload
--    (which matches kendras by name) ambiguous.
select lower(name) as duplicated_name, count(*)
  from public.kendras
 group by lower(name)
having count(*) > 1;

-- 4. kendras in the table now — this is the list the upload adds to.
select name, latitude, longitude, radius_m, verify_interval_min
  from public.kendras
 order by name;

-- The function itself calls assert_admin(), so it cannot be dry-run from
-- the SQL editor (auth.uid() is null there — it would raise 'Not
-- authorized'). Test it from the dashboard instead: Bulk upload →
-- Kendras → Download template, fill two rows, upload.
