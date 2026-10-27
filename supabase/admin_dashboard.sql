-- =====================================================================
-- Dhan Kharidi Samiksha — ADMIN DASHBOARD backend (migration 2)
-- Run AFTER supabase/schema.sql. Single re-runnable transaction.
-- Paste the WHOLE file into the Supabase SQL editor and run it.
--
-- What it does:
--   * Adds an `admins` allow-list keyed to Supabase Auth users (auth.uid()).
--   * DROPS the old spoofable admin functions that trusted a passed-in id.
--   * Adds secured admin RPCs (email/password login via Supabase Auth) for:
--       overview stats, attendance logs, kendra & staff management,
--       kendra assignment, and bulk Excel enrollment.
-- =====================================================================

begin;

-- ---------------------------------------------------------------------
-- Admin allow-list: links a Supabase Auth user to dashboard admin rights.
-- ---------------------------------------------------------------------
create table if not exists public.admins (
  auth_id    uuid primary key references auth.users(id) on delete cascade,
  full_name  text not null default 'Administrator',
  created_at timestamptz not null default now()
);
alter table public.admins enable row level security;

-- ---------------------------------------------------------------------
-- Guard: raises unless the caller (JWT) is an allow-listed admin.
-- ---------------------------------------------------------------------
create or replace function public.assert_admin()
returns void
language plpgsql stable security definer set search_path = public as $$
begin
  if auth.uid() is null
     or not exists (select 1 from public.admins where auth_id = auth.uid()) then
    raise exception 'Not authorized';
  end if;
end;
$$;

-- ---------------------------------------------------------------------
-- DROP the old, spoofable admin functions (they trusted p_admin_id).
-- ---------------------------------------------------------------------
drop function if exists public.admin_list_kendras(uuid);
drop function if exists public.admin_create_kendra(uuid, text, double precision, double precision, integer, time, time, time, integer);
drop function if exists public.admin_list_staff(uuid);
drop function if exists public.admin_enroll_staff(uuid, text, text, text, text);
drop function if exists public.admin_staff_kendras(uuid, uuid);
drop function if exists public.admin_assign_kendra(uuid, uuid, uuid, boolean);

-- ---------------------------------------------------------------------
-- Secured admin RPCs (all call assert_admin()).
-- ---------------------------------------------------------------------

-- Overview stats for the dashboard home.
create or replace function public.admin_overview()
returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare
  v_today   date := ((now() at time zone 'Asia/Kolkata')::date);
  v_staff   integer; v_kendra integer;
  v_present integer; v_late integer; v_out integer; v_absent integer;
begin
  perform public.assert_admin();
  select count(*) into v_staff  from public.staff where role = 'staff';
  select count(*) into v_kendra from public.kendras;
  select count(*) filter (where check_in_at is not null),
         count(*) filter (where status = 'late'),
         count(*) filter (where check_out_at is not null)
    into v_present, v_late, v_out
    from public.attendance where day = v_today;
  v_absent := greatest(coalesce(v_staff,0) - coalesce(v_present,0), 0);
  return jsonb_build_object(
    'ok', true,
    'today', to_char(v_today,'YYYY-MM-DD'),
    'total_staff', coalesce(v_staff,0),
    'total_kendras', coalesce(v_kendra,0),
    'present_today', coalesce(v_present,0),
    'late_today', coalesce(v_late,0),
    'checked_out_today', coalesce(v_out,0),
    'absent_today', v_absent
  );
end;
$$;

-- Attendance log across all staff, with filters + per-day pulse tallies.
create or replace function public.admin_attendance_log(
  p_from date, p_to date, p_kendra_id uuid, p_search text
) returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare
  v_from date := coalesce(p_from, ((now() at time zone 'Asia/Kolkata')::date) - 6);
  v_to   date := coalesce(p_to,   ((now() at time zone 'Asia/Kolkata')::date));
begin
  perform public.assert_admin();
  return jsonb_build_object('ok', true, 'rows', coalesce((
    select jsonb_agg(to_jsonb(t) order by t.day desc, t.staff_name)
    from (
      select
        to_char(a.day,'YYYY-MM-DD')                                   as day,
        s.full_name                                                   as staff_name,
        s.mobile_number                                               as mobile,
        k.name                                                        as kendra,
        to_char(a.check_in_at  at time zone 'Asia/Kolkata','HH24:MI:SS') as check_in_at,
        to_char(a.check_out_at at time zone 'Asia/Kolkata','HH24:MI:SS') as check_out_at,
        a.status                                                      as status,
        a.work_minutes                                                as work_minutes,
        coalesce(pc.cnt,0)                                            as present_count,
        coalesce(ac.cnt,0)                                            as absent_count
      from public.attendance a
      join public.staff s on s.id = a.staff_id
      left join public.kendras k on k.id = a.check_in_kendra_id
      left join lateral (
        select count(*) cnt from public.presence_pulses p
        where p.staff_id = a.staff_id and p.day = a.day and p.inside
      ) pc on true
      left join lateral (
        select count(*) cnt from public.presence_pulses p
        where p.staff_id = a.staff_id and p.day = a.day and not p.inside
      ) ac on true
      where a.day between v_from and v_to
        and (p_kendra_id is null or a.check_in_kendra_id = p_kendra_id)
        and (coalesce(btrim(p_search),'') = ''
             or s.full_name ilike '%'||p_search||'%'
             or s.mobile_number like '%'||p_search||'%')
      order by a.day desc, s.full_name
      limit 2000
    ) t
  ), '[]'::jsonb));
end;
$$;

create or replace function public.admin_list_kendras()
returns jsonb
language plpgsql stable security definer set search_path = public as $$
begin
  perform public.assert_admin();
  return jsonb_build_object('ok', true, 'rows', coalesce((
    select jsonb_agg(jsonb_build_object(
             'id', k.id, 'name', k.name, 'latitude', k.latitude, 'longitude', k.longitude,
             'radius_m', k.radius_m,
             'check_in_start', to_char(k.check_in_start,'HH24:MI'),
             'check_in_end', to_char(k.check_in_end,'HH24:MI'),
             'day_end', to_char(k.day_end,'HH24:MI'),
             'verify_interval_min', k.verify_interval_min
           ) order by k.name)
    from public.kendras k
  ), '[]'::jsonb));
end;
$$;

create or replace function public.admin_create_kendra(
  p_name text, p_lat double precision, p_long double precision, p_radius_m integer,
  p_check_in_start time, p_check_in_end time, p_day_end time, p_verify_interval_min integer
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare v_id uuid;
begin
  perform public.assert_admin();
  insert into public.kendras
    (name, latitude, longitude, radius_m, check_in_start, check_in_end, day_end, verify_interval_min)
  values
    (btrim(p_name), p_lat, p_long, coalesce(p_radius_m,100),
     p_check_in_start, p_check_in_start + interval '30 minutes',
     coalesce(p_day_end,'18:00'), coalesce(p_verify_interval_min,30))
  returning id into v_id;
  return jsonb_build_object('ok', true, 'id', v_id);
end;
$$;

create or replace function public.admin_list_staff()
returns jsonb
language plpgsql stable security definer set search_path = public as $$
begin
  perform public.assert_admin();
  return jsonb_build_object('ok', true, 'rows', coalesce((
    select jsonb_agg(jsonb_build_object(
             'id', s.id, 'full_name', s.full_name, 'mobile_number', s.mobile_number,
             'role', s.role, 'duty_type', s.duty_type,
             'kendra_names', (select string_agg(k.name, ', ' order by k.name)
                              from public.staff_kendra sk join public.kendras k on k.id = sk.kendra_id
                              where sk.staff_id = s.id)
           ) order by s.full_name)
    from public.staff s
  ), '[]'::jsonb));
end;
$$;

create or replace function public.admin_enroll_staff(
  p_full_name text, p_mobile text, p_role text, p_duty_type text
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare v_id uuid;
begin
  perform public.assert_admin();
  if exists (select 1 from public.staff where mobile_number = btrim(p_mobile)) then
    return jsonb_build_object('ok', false, 'error', 'Mobile number already registered.');
  end if;
  insert into public.staff (full_name, mobile_number, role, duty_type)
  values (btrim(p_full_name), btrim(p_mobile),
          coalesce(nullif(lower(btrim(p_role)),''),'staff'),
          coalesce(nullif(lower(btrim(p_duty_type)),''),'fixed'))
  returning id into v_id;
  return jsonb_build_object('ok', true, 'id', v_id);
end;
$$;

create or replace function public.admin_staff_kendras(p_staff_id uuid)
returns jsonb
language plpgsql stable security definer set search_path = public as $$
begin
  perform public.assert_admin();
  return jsonb_build_object('ok', true, 'kendra_ids', coalesce((
    select jsonb_agg(kendra_id) from public.staff_kendra where staff_id = p_staff_id
  ), '[]'::jsonb));
end;
$$;

create or replace function public.admin_assign_kendra(
  p_staff_id uuid, p_kendra_id uuid, p_allowed boolean
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare v_duty text;
begin
  perform public.assert_admin();
  select duty_type into v_duty from public.staff where id = p_staff_id;
  if p_allowed then
    if v_duty = 'fixed' then
      delete from public.staff_kendra where staff_id = p_staff_id;
    end if;
    insert into public.staff_kendra (staff_id, kendra_id) values (p_staff_id, p_kendra_id)
    on conflict do nothing;
  else
    delete from public.staff_kendra where staff_id = p_staff_id and kendra_id = p_kendra_id;
  end if;
  return jsonb_build_object('ok', true);
end;
$$;

-- ---------------------------------------------------------------------
-- Bulk enrollment from an Excel-parsed JSON array.
-- Each element: {full_name, mobile_number, role, duty_type, kendra_names}
-- kendra_names is a comma-separated string of existing kendra names.
-- Returns a per-row result array so the UI can show successes/failures.
-- ---------------------------------------------------------------------
create or replace function public.admin_bulk_enroll(p_rows jsonb)
returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_row      jsonb;
  v_res      jsonb := '[]'::jsonb;
  v_name     text; v_mobile text; v_role text; v_duty text; v_kraw text;
  v_staff_id uuid; v_kid uuid; v_kname text; v_knames text[]; v_first boolean;
begin
  perform public.assert_admin();
  if jsonb_typeof(p_rows) <> 'array' then
    return jsonb_build_object('ok', false, 'error', 'Expected an array of rows.');
  end if;

  for v_row in select * from jsonb_array_elements(p_rows) loop
    v_name   := btrim(coalesce(v_row->>'full_name',''));
    v_mobile := btrim(coalesce(v_row->>'mobile_number',''));
    v_role   := lower(btrim(coalesce(v_row->>'role','staff')));
    v_duty   := lower(btrim(coalesce(v_row->>'duty_type','fixed')));
    v_kraw   := coalesce(v_row->>'kendra_names','');

    if v_name = '' or v_mobile = '' then
      v_res := v_res || jsonb_build_object('mobile', v_mobile, 'name', v_name,
        'ok', false, 'error', 'Name and mobile number are required.');
      continue;
    end if;
    if v_role not in ('staff','admin') then v_role := 'staff'; end if;
    if v_duty not in ('fixed','multi') then v_duty := 'fixed'; end if;

    if exists (select 1 from public.staff where mobile_number = v_mobile) then
      v_res := v_res || jsonb_build_object('mobile', v_mobile, 'name', v_name,
        'ok', false, 'error', 'Mobile number already registered.');
      continue;
    end if;

    insert into public.staff (full_name, mobile_number, role, duty_type)
    values (v_name, v_mobile, v_role, v_duty) returning id into v_staff_id;

    v_knames := string_to_array(v_kraw, ',');
    v_first  := true;
    if v_knames is not null then
      foreach v_kname in array v_knames loop
        v_kname := btrim(v_kname);
        if v_kname = '' then continue; end if;
        select id into v_kid from public.kendras where lower(name) = lower(v_kname) limit 1;
        if v_kid is null then
          delete from public.staff where id = v_staff_id;
          v_res := v_res || jsonb_build_object('mobile', v_mobile, 'name', v_name,
            'ok', false, 'error', 'Kendra not found: ' || v_kname);
          v_staff_id := null;
          exit;
        end if;
        if v_duty = 'fixed' and not v_first then exit; end if;
        insert into public.staff_kendra (staff_id, kendra_id) values (v_staff_id, v_kid)
        on conflict do nothing;
        v_first := false;
      end loop;
    end if;

    if v_staff_id is not null then
      v_res := v_res || jsonb_build_object('mobile', v_mobile, 'name', v_name,
        'ok', true, 'staff_id', v_staff_id);
    end if;
  end loop;

  return jsonb_build_object('ok', true, 'results', v_res);
end;
$$;

-- ---------------------------------------------------------------------
-- Grants: dashboard admins authenticate via Supabase Auth -> `authenticated`.
-- ---------------------------------------------------------------------
grant execute on function public.admin_overview() to authenticated;
grant execute on function public.admin_attendance_log(date, date, uuid, text) to authenticated;
grant execute on function public.admin_list_kendras() to authenticated;
grant execute on function public.admin_create_kendra(text, double precision, double precision, integer, time, time, time, integer) to authenticated;
grant execute on function public.admin_list_staff() to authenticated;
grant execute on function public.admin_enroll_staff(text, text, text, text) to authenticated;
grant execute on function public.admin_staff_kendras(uuid) to authenticated;
grant execute on function public.admin_assign_kendra(uuid, uuid, boolean) to authenticated;
grant execute on function public.admin_bulk_enroll(jsonb) to authenticated;

commit;

-- =====================================================================
-- CREATE YOUR FIRST ADMIN (do this once, after the migration above)
--
-- 1) Supabase dashboard -> Authentication -> Users -> "Add user" ->
--    enter an email + password, tick "Auto Confirm User", create it.
-- 2) Copy that user's UUID (the "User UID" column).
-- 3) In the SQL editor run (replace the UUID and name):
--
--    insert into public.admins (auth_id, full_name)
--    values ('00000000-0000-0000-0000-000000000000', 'District Admin')
--    on conflict (auth_id) do nothing;
--
-- 4) Log into the dashboard with that email + password.
-- =====================================================================

-- ---------------------------------------------------------------------
-- VERIFICATION
-- ---------------------------------------------------------------------
select table_name from information_schema.tables
 where table_schema = 'public' and table_name = 'admins';

select routine_name from information_schema.routines
 where routine_schema = 'public'
   and routine_name in ('assert_admin','admin_overview','admin_attendance_log',
                        'admin_list_kendras','admin_create_kendra','admin_list_staff',
                        'admin_enroll_staff','admin_staff_kendras','admin_assign_kendra',
                        'admin_bulk_enroll')
 order by routine_name;

-- Should return 0 rows (old spoofable p_admin_id functions removed):
select p.proname, pg_get_function_identity_arguments(p.oid) as args
from pg_proc p join pg_namespace n on n.oid = p.pronamespace
where n.nspname = 'public'
  and p.proname like 'admin\_%'
  and pg_get_function_arguments(p.oid) like '%p_admin_id%';

