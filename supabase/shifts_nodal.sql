-- =====================================================================
-- Dhan Kharidi Samiksha — SHIFTS PER ASSIGNMENT + NODAL OFFICERS (migration 7)
-- Run AFTER supabase/strict_monitor.sql. Single re-runnable transaction.
-- Paste the WHOLE file into the Supabase SQL editor and run it.
--
-- What changes:
--   * Shift timing moves OFF the kendra and ONTO the staff-kendra
--     assignment, so one kendra can host several employees on different
--     shifts. Existing assignments inherit the old kendra timings, so
--     nobody loses their window.
--   * staff.employee_code  — required + unique, shown everywhere.
--   * staff.category       — 'staff' (one kendra, fixed shift) or 'nodal'
--     (visits many kendras, no fixed time). Migrated from duty_type
--     fixed/multi; duty_type is then dropped.
--   * public.visits        — nodal arrival stamps: a face-checked On Duty
--     at each kendra, closed by the last presence pulse seen there.
--   * kendras keeps only location + verify_interval_min (monitoring
--     cadence, not shift timing).
--   * New admin RPC admin_nodal_visits() = nodal visit report; the
--     assign / enroll / list RPCs now carry employee code, category and
--     per-assignment shift timing.
-- =====================================================================

begin;

-- ---------------------------------------------------------------------
-- 1. staff: employee code + category (migrated from duty_type)
-- ---------------------------------------------------------------------
alter table public.staff add column if not exists employee_code text;
alter table public.staff add column if not exists category text not null default 'staff'
  check (category in ('staff','nodal'));

do $$
begin
  if exists (select 1 from information_schema.columns
              where table_schema = 'public' and table_name = 'staff'
                and column_name = 'duty_type') then
    update public.staff
       set category = case when duty_type = 'multi' then 'nodal' else 'staff' end;
    alter table public.staff drop column duty_type;
  end if;
end $$;

-- Backfill a readable unique code for staff enrolled before this migration.
with numbered as (
  select id,
         'EMP-' || lpad((row_number() over (order by created_at, id))::text, 4, '0') as code
  from public.staff
)
update public.staff s
   set employee_code = n.code
  from numbered n
 where s.id = n.id and s.employee_code is null;

alter table public.staff alter column employee_code set not null;
create unique index if not exists ux_staff_employee_code on public.staff (employee_code);

-- ---------------------------------------------------------------------
-- 2. staff_kendra: the shift now belongs to the assignment
-- ---------------------------------------------------------------------
alter table public.staff_kendra add column if not exists check_in_start time;
alter table public.staff_kendra add column if not exists check_in_end   time;
alter table public.staff_kendra add column if not exists day_end        time;

-- Copy each kendra's old timings into the assignments that already exist.
do $$
begin
  if exists (select 1 from information_schema.columns
              where table_schema = 'public' and table_name = 'kendras'
                and column_name = 'check_in_start') then
    update public.staff_kendra sk
       set check_in_start = k.check_in_start,
           check_in_end   = k.check_in_end,
           day_end        = k.day_end
      from public.kendras k
     where k.id = sk.kendra_id
       and sk.check_in_start is null;
  end if;
end $$;

-- ---------------------------------------------------------------------
-- 3. visits: nodal arrival stamps (check-in only, no off-duty)
-- ---------------------------------------------------------------------
create table if not exists public.visits (
  id            uuid primary key default gen_random_uuid(),
  staff_id      uuid not null references public.staff(id)   on delete cascade,
  day           date not null,
  kendra_id     uuid not null references public.kendras(id) on delete cascade,
  check_in_at   timestamptz not null default now(),
  check_in_lat  double precision,
  check_in_long double precision,
  last_pulse_at timestamptz,                 -- visit closes at the last pulse seen here
  unique (staff_id, day, kendra_id)
);

alter table public.visits enable row level security;

create index if not exists idx_visits_day       on public.visits (day desc);
create index if not exists idx_visits_staff_day on public.visits (staff_id, day desc);

-- ---------------------------------------------------------------------
-- 4. Nearest allowed kendra: windows now come from the assignment.
--    (Redefined BEFORE the kendra timing columns are dropped.)
-- ---------------------------------------------------------------------
create or replace function public.match_allowed_kendra(
  p_staff_id uuid, p_lat double precision, p_long double precision
) returns table (
  kendra_id   uuid,
  kendra_name text,
  dist_m      double precision,
  inside      boolean,
  win_start   time,
  win_end     time,
  win_day_end time,
  verify_min  integer
)
language sql stable security definer set search_path = public as $$
  select k.id,
         k.name,
         public.distance_m(p_lat, p_long, k.latitude, k.longitude),
         (public.distance_m(p_lat, p_long, k.latitude, k.longitude) <= k.radius_m),
         sk.check_in_start, sk.check_in_end, sk.day_end, k.verify_interval_min
  from public.staff_kendra sk
  join public.kendras k on k.id = sk.kendra_id
  where sk.staff_id = p_staff_id
  order by public.distance_m(p_lat, p_long, k.latitude, k.longitude) asc
$$;

-- ---------------------------------------------------------------------
-- 5. Kendras are location + monitoring cadence only.
-- ---------------------------------------------------------------------
alter table public.kendras drop column if exists check_in_start;
alter table public.kendras drop column if exists check_in_end;
alter table public.kendras drop column if exists day_end;

-- ---------------------------------------------------------------------
-- 6. LOGIN: employee code + category; kendras carry their shift window.
-- ---------------------------------------------------------------------
create or replace function public.login_by_mobile(p_mobile text)
returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare
  v_staff public.staff%rowtype;
begin
  select * into v_staff from public.staff where mobile_number = btrim(p_mobile);

  if v_staff.id is null then
    return jsonb_build_object('ok', false, 'error', 'Mobile number not registered.');
  end if;

  return jsonb_build_object(
    'ok', true,
    'staff', jsonb_build_object(
      'id', v_staff.id,
      'full_name', v_staff.full_name,
      'employee_code', v_staff.employee_code,
      'mobile_number', v_staff.mobile_number,
      'role', v_staff.role,
      'category', v_staff.category,
      'face_template', v_staff.face_template
    ),
    'kendras', coalesce((
      select jsonb_agg(jsonb_build_object(
               'id', k.id, 'name', k.name,
               'latitude', k.latitude, 'longitude', k.longitude, 'radius_m', k.radius_m,
               'check_in_start', to_char(sk.check_in_start,'HH24:MI'),
               'check_in_end',   to_char(sk.check_in_end,'HH24:MI'),
               'day_end',        to_char(sk.day_end,'HH24:MI'),
               'verify_interval_min', k.verify_interval_min
             ) order by k.name)
      from public.staff_kendra sk join public.kendras k on k.id = sk.kendra_id
      where sk.staff_id = v_staff.id
    ), '[]'::jsonb)
  );
end;
$$;

-- ---------------------------------------------------------------------
-- 7. CHECK-IN
--    nodal : stamps a visit at whichever assigned kendra they are inside
--            (no time window, no attendance row, no off-duty).
--    staff : the assignment's shift window, +30 min buffer, +15 min late.
-- ---------------------------------------------------------------------
create or replace function public.record_check_in(
  p_staff_id uuid, p_lat double precision, p_long double precision
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_k      record;
  v_now    timestamp := (now() at time zone 'Asia/Kolkata');
  v_date   date := ((now() at time zone 'Asia/Kolkata')::date);
  v_att    public.attendance%rowtype;
  v_visit  public.visits%rowtype;
  v_cat    text;
  v_status text;
  v_count  integer;
begin
  select category into v_cat from public.staff where id = p_staff_id;
  if v_cat is null then
    return jsonb_build_object('ok', false, 'error', 'Staff not found.');
  end if;

  select * into v_k from public.match_allowed_kendra(p_staff_id, p_lat, p_long) limit 1;
  if v_k.kendra_id is null then
    return jsonb_build_object('ok', false, 'error', 'No duty kendra assigned to this staff.');
  end if;

  if not v_k.inside then
    return jsonb_build_object('ok', false, 'reason', 'outside_geofence',
      'error', 'You are outside your allowed duty area.',
      'kendra_name', v_k.kendra_name, 'distance_m', round(v_k.dist_m::numeric,1));
  end if;

  -- ---------------- nodal: arrival stamp ----------------
  if v_cat = 'nodal' then
    select * into v_visit from public.visits
     where staff_id = p_staff_id and day = v_date and kendra_id = v_k.kendra_id;

    if v_visit.id is not null then
      return jsonb_build_object('ok', true, 'status', 'already', 'category', 'nodal',
        'message', 'Visit already recorded at ' || v_k.kendra_name || ' today.',
        'check_in_at', to_char(v_visit.check_in_at at time zone 'Asia/Kolkata','HH24:MI:SS'),
        'kendra_id', v_k.kendra_id, 'kendra_name', v_k.kendra_name,
        'verify_interval_min', v_k.verify_min,
        'distance_m', round(v_k.dist_m::numeric,1));
    end if;

    insert into public.visits
      (staff_id, day, kendra_id, check_in_at, check_in_lat, check_in_long, last_pulse_at)
    values
      (p_staff_id, v_date, v_k.kendra_id, now(), p_lat, p_long, now());

    select count(*) into v_count from public.visits
     where staff_id = p_staff_id and day = v_date;

    return jsonb_build_object('ok', true, 'status', 'visit_recorded', 'category', 'nodal',
      'message', 'Visit recorded at ' || v_k.kendra_name || '.',
      'check_in_at', to_char((now() at time zone 'Asia/Kolkata'),'HH24:MI:SS'),
      'kendra_id', v_k.kendra_id, 'kendra_name', v_k.kendra_name,
      'verify_interval_min', v_k.verify_min,
      'visit_count', v_count,
      'distance_m', round(v_k.dist_m::numeric,1));
  end if;

  -- ---------------- staff: shift window ----------------
  if v_k.win_start is not null and v_k.win_end is not null
     and (v_now::time < v_k.win_start or v_now::time > v_k.win_end) then
    return jsonb_build_object('ok', false, 'reason', 'outside_window',
      'error', 'Outside your shift check-in time.',
      'kendra_name', v_k.kendra_name,
      'shift_start', to_char(v_k.win_start,'HH24:MI'),
      'shift_end',   to_char(v_k.win_end,'HH24:MI'),
      'distance_m', round(v_k.dist_m::numeric,1));
  end if;

  select * into v_att from public.attendance where staff_id = p_staff_id and day = v_date;
  if v_att.id is not null and v_att.check_in_at is not null then
    return jsonb_build_object('ok', true, 'status', 'already', 'category', 'staff',
      'message', 'Already checked in for today.',
      'check_in_at', to_char(v_att.check_in_at at time zone 'Asia/Kolkata','HH24:MI:SS'),
      'kendra_id', v_att.check_in_kendra_id, 'distance_m', round(v_k.dist_m::numeric,1));
  end if;

  v_status := case
    when v_k.win_start is not null and v_now::time > (v_k.win_start + interval '15 minutes')
      then 'late' else 'present' end;

  insert into public.attendance (staff_id, day, check_in_at, check_in_kendra_id, check_in_lat, check_in_long, status)
  values (p_staff_id, v_date, now(), v_k.kendra_id, p_lat, p_long, v_status)
  on conflict (staff_id, day) do update
    set check_in_at = excluded.check_in_at,
        check_in_kendra_id = excluded.check_in_kendra_id,
        check_in_lat = excluded.check_in_lat,
        check_in_long = excluded.check_in_long,
        status = excluded.status;

  return jsonb_build_object('ok', true, 'status', 'checked_in', 'category', 'staff',
    'message', 'Check-in recorded.',
    'check_in_at', to_char((now() at time zone 'Asia/Kolkata'),'HH24:MI:SS'),
    'attendance_status', v_status,
    'kendra_id', v_k.kendra_id, 'kendra_name', v_k.kendra_name,
    'day_end', to_char(v_k.win_day_end,'HH24:MI'),
    'verify_interval_min', v_k.verify_min,
    'distance_m', round(v_k.dist_m::numeric,1));
end;
$$;

-- ---------------------------------------------------------------------
-- 8. PRESENCE PULSE
--    nodal : also extends the open visit at the kendra they are inside.
--    staff : unchanged, incl. left-area auto check-out after 30 min out.
-- ---------------------------------------------------------------------
create or replace function public.record_pulse(
  p_staff_id uuid, p_lat double precision, p_long double precision
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_k        record;
  v_att      public.attendance%rowtype;
  v_date     date := ((now() at time zone 'Asia/Kolkata')::date);
  v_cat      text;
  v_inside   boolean := false;
  v_absent   integer;
  v_present  integer;
  v_rec      record;
  v_run_start timestamptz := null;   -- oldest pulse of the trailing absent run
  v_run_min  integer := 0;
  v_last_present timestamptz;
  v_work     integer;
  v_auto     boolean := false;
  v_visit    boolean := false;
begin
  select category into v_cat from public.staff where id = p_staff_id;

  select * into v_k from public.match_allowed_kendra(p_staff_id, p_lat, p_long) limit 1;
  if v_k.kendra_id is null then
    return jsonb_build_object('ok', false, 'error', 'No duty kendra assigned.');
  end if;

  v_inside := v_k.inside;

  insert into public.presence_pulses (staff_id, day, inside, kendra_id, distance_m, lat, long)
  values (p_staff_id, v_date, v_inside,
          case when v_inside then v_k.kendra_id else null end,
          round(v_k.dist_m::numeric,1), p_lat, p_long);

  select count(*) filter (where inside), count(*) filter (where not inside)
    into v_present, v_absent
  from public.presence_pulses where staff_id = p_staff_id and day = v_date;

  -- Nodal: a pulse inside a kendra keeps that visit open.
  if v_cat = 'nodal' then
    if v_inside then
      update public.visits
         set last_pulse_at = now()
       where staff_id = p_staff_id and day = v_date and kendra_id = v_k.kendra_id;
      v_visit := found;
    end if;

    return jsonb_build_object('ok', true,
      'inside', v_inside,
      'category', 'nodal',
      'kendra_name', case when v_inside then v_k.kendra_name else null end,
      'distance_m', round(v_k.dist_m::numeric,1),
      'at', to_char((now() at time zone 'Asia/Kolkata'),'HH24:MI:SS'),
      'present_count', v_present, 'absent_count', v_absent,
      'visit_open', v_visit,
      'message', case
        when v_inside and v_visit then 'Presence verified at ' || v_k.kendra_name || '.'
        when v_inside then 'Inside ' || v_k.kendra_name || ' — mark On Duty to open the visit.'
        else 'Outside duty area.' end);
  end if;

  -- Staff: left-area auto check-out. A trailing run of absent pulses
  -- spanning >= 30 minutes closes the duty; work time counts verified
  -- presence only.
  if not v_inside then
    select * into v_att from public.attendance where staff_id = p_staff_id and day = v_date;
    if v_att.id is not null and v_att.check_in_at is not null and v_att.check_out_at is null then
      for v_rec in
        select at, inside from public.presence_pulses
         where staff_id = p_staff_id and day = v_date
         order by at desc
      loop
        exit when v_rec.inside;
        v_run_start := v_rec.at;
      end loop;
      if v_run_start is not null then
        v_run_min := round(extract(epoch from (now() - v_run_start)) / 60.0)::integer;
      end if;
      if v_run_min >= 30 then
        select max(at) into v_last_present
          from public.presence_pulses
         where staff_id = p_staff_id and day = v_date and inside;
        v_work := round(extract(epoch from
                    (coalesce(v_last_present, v_att.check_in_at) - v_att.check_in_at)) / 60.0)::integer;
        update public.attendance
           set check_out_at = now(),
               check_out_reason = 'left_area',
               work_minutes = v_work
         where id = v_att.id;
        v_auto := true;
      end if;
    end if;
  end if;

  return jsonb_build_object('ok', true,
    'inside', v_inside,
    'category', 'staff',
    'kendra_name', case when v_inside then v_k.kendra_name else null end,
    'distance_m', round(v_k.dist_m::numeric,1),
    'at', to_char((now() at time zone 'Asia/Kolkata'),'HH24:MI:SS'),
    'present_count', v_present, 'absent_count', v_absent,
    'auto_checked_out', v_auto,
    'check_out_at', case when v_auto
        then to_char((now() at time zone 'Asia/Kolkata'),'HH24:MI:SS') else null end,
    'work_minutes', case when v_auto then v_work else null end,
    'check_out_reason', case when v_auto then 'left_area' else null end,
    'message', case when v_auto then
        'Duty auto-closed: outside the duty area for 30 minutes or more. Work time stored up to the last verified presence.'
      else null end);
end;
$$;

-- ---------------------------------------------------------------------
-- 9. CHECK-OUT: fixed-duty staff only. Nodal officers never mark off duty.
-- ---------------------------------------------------------------------
create or replace function public.record_check_out(
  p_staff_id uuid, p_lat double precision, p_long double precision,
  p_reason text default 'manual'
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_date date := ((now() at time zone 'Asia/Kolkata')::date);
  v_att  public.attendance%rowtype;
  v_k    record;
  v_cat  text;
  v_min  integer;
begin
  select category into v_cat from public.staff where id = p_staff_id;
  if v_cat = 'nodal' then
    return jsonb_build_object('ok', false,
      'error', 'Nodal officers record visits only — there is no off-duty to mark.');
  end if;

  select * into v_att from public.attendance where staff_id = p_staff_id and day = v_date;
  if v_att.id is null or v_att.check_in_at is null then
    return jsonb_build_object('ok', false, 'error', 'No check-in recorded for today.');
  end if;
  if v_att.check_out_at is not null then
    return jsonb_build_object('ok', true, 'status', 'already', 'message', 'Already checked out for today.',
      'check_out_at', to_char(v_att.check_out_at at time zone 'Asia/Kolkata','HH24:MI:SS'),
      'work_minutes', v_att.work_minutes,
      'check_out_reason', v_att.check_out_reason);
  end if;

  select * into v_k from public.match_allowed_kendra(p_staff_id, p_lat, p_long) limit 1;
  v_min := round(extract(epoch from (now() - v_att.check_in_at)) / 60.0)::integer;

  update public.attendance
     set check_out_at = now(),
         check_out_kendra_id = v_k.kendra_id,
         check_out_lat = p_lat,
         check_out_long = p_long,
         work_minutes = v_min,
         check_out_reason = coalesce(p_reason, 'manual')
   where id = v_att.id;

  return jsonb_build_object('ok', true, 'status', 'checked_out', 'message', 'Check-out recorded.',
    'check_out_at', to_char((now() at time zone 'Asia/Kolkata'),'HH24:MI:SS'),
    'work_minutes', v_min,
    'check_out_reason', coalesce(p_reason, 'manual'));
end;
$$;

-- ---------------------------------------------------------------------
-- 10. TODAY snapshot: staff attendance, or the nodal visit list.
-- ---------------------------------------------------------------------
create or replace function public.get_today(p_staff_id uuid)
returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare
  v_date date := ((now() at time zone 'Asia/Kolkata')::date);
  v_att  public.attendance%rowtype;
  v_p    integer; v_a integer; v_last text;
  v_kname text;
  v_cat  text;
begin
  select category into v_cat from public.staff where id = p_staff_id;

  select * into v_att from public.attendance where staff_id = p_staff_id and day = v_date;

  select count(*) filter (where inside), count(*) filter (where not inside),
         to_char(max(at) at time zone 'Asia/Kolkata','HH24:MI:SS')
    into v_p, v_a, v_last
  from public.presence_pulses where staff_id = p_staff_id and day = v_date;

  if v_att.check_in_kendra_id is not null then
    select name into v_kname from public.kendras where id = v_att.check_in_kendra_id;
  end if;

  return jsonb_build_object(
    'ok', true, 'day', to_char(v_date,'YYYY-MM-DD'),
    'category', coalesce(v_cat,'staff'),
    'record', case when v_att.id is null then null else jsonb_build_object(
      'status', v_att.status,
      'check_in_at',  to_char(v_att.check_in_at  at time zone 'Asia/Kolkata','HH24:MI:SS'),
      'check_out_at', to_char(v_att.check_out_at at time zone 'Asia/Kolkata','HH24:MI:SS'),
      'check_in_kendra', v_kname,
      'work_minutes', v_att.work_minutes,
      'check_out_reason', v_att.check_out_reason
    ) end,
    'visits', coalesce((
      select jsonb_agg(jsonb_build_object(
               'kendra_id', vis.kendra_id,
               'kendra', k.name,
               'check_in_at', to_char(vis.check_in_at at time zone 'Asia/Kolkata','HH24:MI:SS'),
               'last_pulse_at', to_char(vis.last_pulse_at at time zone 'Asia/Kolkata','HH24:MI:SS'),
               'minutes', round(extract(epoch from
                          (coalesce(vis.last_pulse_at, vis.check_in_at) - vis.check_in_at)) / 60.0)::integer
             ) order by vis.check_in_at)
      from public.visits vis join public.kendras k on k.id = vis.kendra_id
      where vis.staff_id = p_staff_id and vis.day = v_date
    ), '[]'::jsonb),
    'visit_count', (select count(*) from public.visits
                     where staff_id = p_staff_id and day = v_date),
    'present_count', coalesce(v_p,0),
    'absent_count',  coalesce(v_a,0),
    'last_pulse_at', v_last
  );
end;
$$;

-- ---------------------------------------------------------------------
-- 11. ADMIN: kendras are location + monitoring cadence only.
-- ---------------------------------------------------------------------
drop function if exists public.admin_create_kendra(text, double precision, double precision, integer, time, time, time, integer);
drop function if exists public.admin_update_kendra(uuid, text, double precision, double precision, integer, time, time, time, integer);
drop function if exists public.admin_enroll_staff(text, text, text, text);
drop function if exists public.admin_assign_kendra(uuid, uuid, boolean);

create or replace function public.admin_list_kendras()
returns jsonb
language plpgsql stable security definer set search_path = public as $$
begin
  perform public.assert_admin();
  return jsonb_build_object('ok', true, 'rows', coalesce((
    select jsonb_agg(jsonb_build_object(
             'id', k.id, 'name', k.name, 'latitude', k.latitude, 'longitude', k.longitude,
             'radius_m', k.radius_m,
             'verify_interval_min', k.verify_interval_min,
             'staff_count', (select count(*) from public.staff_kendra sk
                               join public.staff sf on sf.id = sk.staff_id
                              where sk.kendra_id = k.id and sf.category = 'staff'),
             'nodal_count', (select count(*) from public.staff_kendra sk
                               join public.staff nd on nd.id = sk.staff_id
                              where sk.kendra_id = k.id and nd.category = 'nodal')
           ) order by k.name)
    from public.kendras k
  ), '[]'::jsonb));
end;
$$;

create or replace function public.admin_create_kendra(
  p_name text, p_lat double precision, p_long double precision,
  p_radius_m integer, p_verify_interval_min integer default 30
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare v_id uuid;
begin
  perform public.assert_admin();
  if coalesce(btrim(p_name),'') = '' then
    return jsonb_build_object('ok', false, 'error', 'Kendra name is required.');
  end if;
  insert into public.kendras (name, latitude, longitude, radius_m, verify_interval_min)
  values (btrim(p_name), p_lat, p_long, coalesce(p_radius_m,100), coalesce(p_verify_interval_min,30))
  returning id into v_id;
  return jsonb_build_object('ok', true, 'id', v_id);
end;
$$;

create or replace function public.admin_update_kendra(
  p_kendra_id uuid,
  p_name text,
  p_latitude double precision,
  p_longitude double precision,
  p_radius_m integer,
  p_verify_interval_min integer default null
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare v_id uuid;
begin
  perform public.assert_admin();

  if coalesce(btrim(p_name), '') = '' then
    return jsonb_build_object('ok', false, 'error', 'Kendra name is required.');
  end if;

  update public.kendras
     set name                = btrim(p_name),
         latitude            = p_latitude,
         longitude           = p_longitude,
         radius_m            = coalesce(p_radius_m, radius_m),
         verify_interval_min = coalesce(p_verify_interval_min, verify_interval_min)
   where id = p_kendra_id
   returning id into v_id;

  if v_id is null then
    return jsonb_build_object('ok', false, 'error', 'Kendra not found.');
  end if;

  return jsonb_build_object('ok', true, 'id', v_id);
end;
$$;

-- ---------------------------------------------------------------------
-- 12. ADMIN: staff carry an employee code and a category.
-- ---------------------------------------------------------------------
create or replace function public.admin_list_staff()
returns jsonb
language plpgsql stable security definer set search_path = public as $$
begin
  perform public.assert_admin();
  return jsonb_build_object('ok', true, 'rows', coalesce((
    select jsonb_agg(jsonb_build_object(
             'id', s.id, 'full_name', s.full_name,
             'employee_code', s.employee_code,
             'mobile_number', s.mobile_number,
             'role', s.role, 'category', s.category,
             'face_registered', (s.face_template is not null),
             'kendra_names', (select string_agg(
                                k.name || coalesce(' (' || to_char(sk.check_in_start,'HH24:MI') || ')',''),
                                ', ' order by k.name)
                              from public.staff_kendra sk join public.kendras k on k.id = sk.kendra_id
                              where sk.staff_id = s.id)
           ) order by s.full_name)
    from public.staff s
  ), '[]'::jsonb));
end;
$$;

create or replace function public.admin_enroll_staff(
  p_full_name text, p_mobile text, p_employee_code text, p_role text, p_category text
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare v_id uuid; v_code text;
begin
  perform public.assert_admin();

  if coalesce(btrim(p_full_name),'') = '' then
    return jsonb_build_object('ok', false, 'error', 'Full name is required.');
  end if;
  if coalesce(btrim(p_mobile),'') = '' then
    return jsonb_build_object('ok', false, 'error', 'Mobile number is required.');
  end if;
  v_code := upper(btrim(coalesce(p_employee_code,'')));
  if v_code = '' then
    return jsonb_build_object('ok', false, 'error', 'Employee code is required.');
  end if;
  if exists (select 1 from public.staff where mobile_number = btrim(p_mobile)) then
    return jsonb_build_object('ok', false, 'error', 'Mobile number already registered.');
  end if;
  if exists (select 1 from public.staff where employee_code = v_code) then
    return jsonb_build_object('ok', false, 'error', 'Employee code already registered.');
  end if;

  insert into public.staff (full_name, mobile_number, employee_code, role, category)
  values (btrim(p_full_name), btrim(p_mobile), v_code,
          coalesce(nullif(lower(btrim(p_role)),''),'staff'),
          case when lower(btrim(coalesce(p_category,''))) = 'nodal' then 'nodal' else 'staff' end)
  returning id into v_id;
  return jsonb_build_object('ok', true, 'id', v_id);
end;
$$;

-- Assignments of one staff member, with the shift stored on each.
create or replace function public.admin_staff_kendras(p_staff_id uuid)
returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare v_cat text;
begin
  perform public.assert_admin();
  select category into v_cat from public.staff where id = p_staff_id;
  return jsonb_build_object('ok', true,
    'category', coalesce(v_cat,'staff'),
    'assignments', coalesce((
      select jsonb_agg(jsonb_build_object(
               'kendra_id', sk.kendra_id,
               'kendra_name', k.name,
               'check_in_start', to_char(sk.check_in_start,'HH24:MI'),
               'check_in_end',   to_char(sk.check_in_end,'HH24:MI'),
               'day_end',        to_char(sk.day_end,'HH24:MI')
             ) order by k.name)
      from public.staff_kendra sk join public.kendras k on k.id = sk.kendra_id
      where sk.staff_id = p_staff_id
    ), '[]'::jsonb));
end;
$$;

-- Assign / unassign a kendra. For category='staff' the shift timing is
-- required and the assignment replaces any earlier one (exactly one
-- kendra). For category='nodal' several kendras are allowed with no
-- timing at all.
create or replace function public.admin_assign_kendra(
  p_staff_id uuid, p_kendra_id uuid, p_allowed boolean,
  p_check_in_start time default null, p_day_end time default null
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare v_cat text;
begin
  perform public.assert_admin();
  select category into v_cat from public.staff where id = p_staff_id;
  if v_cat is null then
    return jsonb_build_object('ok', false, 'error', 'Staff not found.');
  end if;

  if not p_allowed then
    delete from public.staff_kendra where staff_id = p_staff_id and kendra_id = p_kendra_id;
    return jsonb_build_object('ok', true);
  end if;

  if v_cat = 'nodal' then
    insert into public.staff_kendra (staff_id, kendra_id, check_in_start, check_in_end, day_end)
    values (p_staff_id, p_kendra_id, null, null, null)
    on conflict (staff_id, kendra_id) do update
      set check_in_start = null, check_in_end = null, day_end = null;
    return jsonb_build_object('ok', true);
  end if;

  if p_check_in_start is null then
    return jsonb_build_object('ok', false, 'error', 'Select the shift check-in time for this staff member.');
  end if;

  -- Fixed-duty staff report to exactly one kendra.
  delete from public.staff_kendra where staff_id = p_staff_id;

  insert into public.staff_kendra (staff_id, kendra_id, check_in_start, check_in_end, day_end)
  values (p_staff_id, p_kendra_id,
          p_check_in_start,
          p_check_in_start + interval '30 minutes',
          coalesce(p_day_end,'18:00'));

  return jsonb_build_object('ok', true,
    'check_in_end', to_char(p_check_in_start + interval '30 minutes','HH24:MI'));
end;
$$;

-- ---------------------------------------------------------------------
-- 13. ADMIN: bulk enrollment from an Excel-parsed JSON array.
-- Each element: {full_name, employee_code, mobile_number, role,
--                category, kendra_names, check_in_start, day_end}
-- kendra_names is a comma-separated string of existing kendra names.
-- check_in_start / day_end (HH24:MI) are optional; when present they are
-- written onto every kendra assignment of that row.
-- ---------------------------------------------------------------------
create or replace function public.admin_bulk_enroll(p_rows jsonb)
returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_row      jsonb;
  v_res      jsonb := '[]'::jsonb;
  v_name     text; v_mobile text; v_code text; v_role text; v_cat text; v_kraw text;
  v_start    time; v_end_day time; v_txt text;
  v_staff_id uuid; v_kid uuid; v_kname text; v_knames text[]; v_first boolean;
begin
  perform public.assert_admin();
  if jsonb_typeof(p_rows) <> 'array' then
    return jsonb_build_object('ok', false, 'error', 'Expected an array of rows.');
  end if;

  for v_row in select * from jsonb_array_elements(p_rows) loop
    v_name   := btrim(coalesce(v_row->>'full_name',''));
    v_mobile := btrim(coalesce(v_row->>'mobile_number',''));
    v_code   := upper(btrim(coalesce(v_row->>'employee_code','')));
    v_role   := lower(btrim(coalesce(v_row->>'role','staff')));
    v_cat    := lower(btrim(coalesce(v_row->>'category', v_row->>'duty_type','staff')));
    v_kraw   := coalesce(v_row->>'kendra_names','');

    -- Only a clean HH:MM[:SS] cell becomes a shift; anything else means
    -- "no shift set" instead of aborting the whole import.
    v_txt     := btrim(coalesce(v_row->>'check_in_start',''));
    v_start   := case when v_txt ~ '^\d{1,2}:\d{2}(:\d{2})?$' then v_txt::time end;
    v_txt     := btrim(coalesce(v_row->>'day_end',''));
    v_end_day := case when v_txt ~ '^\d{1,2}:\d{2}(:\d{2})?$' then v_txt::time end;

    if v_name = '' or v_mobile = '' or v_code = '' then
      v_res := v_res || jsonb_build_object('mobile', v_mobile, 'name', v_name, 'code', v_code,
        'ok', false, 'error', 'Name, employee code and mobile number are required.');
      continue;
    end if;
    if v_role not in ('staff','admin') then v_role := 'staff'; end if;
    if v_cat in ('nodal','multi') then v_cat := 'nodal'; else v_cat := 'staff'; end if;

    if exists (select 1 from public.staff where mobile_number = v_mobile) then
      v_res := v_res || jsonb_build_object('mobile', v_mobile, 'name', v_name, 'code', v_code,
        'ok', false, 'error', 'Mobile number already registered.');
      continue;
    end if;
    if exists (select 1 from public.staff where employee_code = v_code) then
      v_res := v_res || jsonb_build_object('mobile', v_mobile, 'name', v_name, 'code', v_code,
        'ok', false, 'error', 'Employee code already registered.');
      continue;
    end if;

    insert into public.staff (full_name, mobile_number, employee_code, role, category)
    values (v_name, v_mobile, v_code, v_role, v_cat) returning id into v_staff_id;

    v_knames := string_to_array(v_kraw, ',');
    v_first  := true;
    if v_knames is not null then
      foreach v_kname in array v_knames loop
        v_kname := btrim(v_kname);
        if v_kname = '' then continue; end if;
        select id into v_kid from public.kendras where lower(name) = lower(v_kname) limit 1;
        if v_kid is null then
          delete from public.staff where id = v_staff_id;
          v_res := v_res || jsonb_build_object('mobile', v_mobile, 'name', v_name, 'code', v_code,
            'ok', false, 'error', 'Kendra not found: ' || v_kname);
          v_staff_id := null;
          exit;
        end if;
        if v_cat = 'staff' and not v_first then exit; end if;
        insert into public.staff_kendra (staff_id, kendra_id, check_in_start, check_in_end, day_end)
        values (v_staff_id, v_kid,
                case when v_cat = 'staff' then v_start end,
                case when v_cat = 'staff' and v_start is not null
                     then v_start + interval '30 minutes' end,
                case when v_cat = 'staff' then coalesce(v_end_day,'18:00') end)
        on conflict do nothing;
        v_first := false;
      end loop;
    end if;

    if v_staff_id is not null then
      v_res := v_res || jsonb_build_object('mobile', v_mobile, 'name', v_name, 'code', v_code,
        'ok', true, 'staff_id', v_staff_id);
    end if;
  end loop;

  return jsonb_build_object('ok', true, 'results', v_res);
end;
$$;

-- ---------------------------------------------------------------------
-- 14. ADMIN: nodal visit report (arrival + last pulse per kendra per day)
-- ---------------------------------------------------------------------
create or replace function public.admin_nodal_visits(
  p_from date, p_to date, p_kendra_id uuid, p_search text
) returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare
  v_from date := coalesce(p_from, ((now() at time zone 'Asia/Kolkata')::date) - 6);
  v_to   date := coalesce(p_to,   ((now() at time zone 'Asia/Kolkata')::date));
begin
  perform public.assert_admin();
  return jsonb_build_object('ok', true, 'rows', coalesce((
    select jsonb_agg(to_jsonb(t) order by t.day desc, t.arrived_at desc)
    from (
      select
        to_char(v.day,'YYYY-MM-DD')                                      as day,
        s.full_name                                                      as staff_name,
        s.employee_code                                                  as employee_code,
        s.mobile_number                                                  as mobile,
        k.name                                                           as kendra,
        to_char(v.check_in_at at time zone 'Asia/Kolkata','HH24:MI:SS')  as arrived_at,
        to_char(v.last_pulse_at at time zone 'Asia/Kolkata','HH24:MI:SS') as last_seen_at,
        round(extract(epoch from
              (coalesce(v.last_pulse_at, v.check_in_at) - v.check_in_at)) / 60.0)::integer as minutes_at_site,
        coalesce(pc.cnt,0)                                               as pulses_inside,
        v.check_in_lat                                                   as latitude,
        v.check_in_long                                                  as longitude
      from public.visits v
      join public.staff s   on s.id = v.staff_id
      join public.kendras k on k.id = v.kendra_id
      left join lateral (
        select count(*) cnt from public.presence_pulses p
         where p.staff_id = v.staff_id and p.day = v.day
           and p.kendra_id = v.kendra_id and p.inside
      ) pc on true
      where v.day between v_from and v_to
        and (p_kendra_id is null or v.kendra_id = p_kendra_id)
        and (coalesce(btrim(p_search),'') = ''
             or s.full_name ilike '%'||p_search||'%'
             or s.mobile_number like '%'||p_search||'%'
             or s.employee_code ilike '%'||p_search||'%')
      order by v.day desc, v.check_in_at desc
      limit 2000
    ) t
  ), '[]'::jsonb));
end;
$$;

-- ---------------------------------------------------------------------
-- 15. ADMIN: overview also counts nodal officers and today's visits.
-- ---------------------------------------------------------------------
create or replace function public.admin_overview()
returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare
  v_today   date := ((now() at time zone 'Asia/Kolkata')::date);
  v_staff   integer; v_kendra integer; v_nodal integer; v_visits integer;
  v_present integer; v_late integer; v_out integer; v_absent integer;
begin
  perform public.assert_admin();
  select count(*) filter (where category = 'staff'),
         count(*) filter (where category = 'nodal')
    into v_staff, v_nodal
  from public.staff where role = 'staff';
  select count(*) into v_kendra from public.kendras;
  select count(*) into v_visits from public.visits where day = v_today;
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
    'total_nodal', coalesce(v_nodal,0),
    'total_kendras', coalesce(v_kendra,0),
    'present_today', coalesce(v_present,0),
    'late_today', coalesce(v_late,0),
    'checked_out_today', coalesce(v_out,0),
    'absent_today', v_absent,
    'nodal_visits_today', coalesce(v_visits,0)
  );
end;
$$;

-- ---------------------------------------------------------------------
-- 16. Attendance log: show the employee code next to the name.
-- ---------------------------------------------------------------------
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
        s.employee_code                                               as employee_code,
        s.mobile_number                                               as mobile,
        k.name                                                        as kendra,
        to_char(a.check_in_at  at time zone 'Asia/Kolkata','HH24:MI:SS') as check_in_at,
        to_char(a.check_out_at at time zone 'Asia/Kolkata','HH24:MI:SS') as check_out_at,
        a.status                                                      as status,
        a.work_minutes                                                as work_minutes,
        coalesce(pc.cnt,0)                                            as present_count,
        coalesce(ac.cnt,0)                                            as absent_count,
        a.check_out_reason                                            as check_out_reason,
        coalesce(st.absent_streak_min,0)                              as absent_streak_min,
        coalesce(sl.silence_min,0)                                    as silence_min
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
      left join lateral (
        -- longest run of consecutive absent pulses, in minutes
        select coalesce(max(run_min),0)::integer as absent_streak_min
        from (
          select grp, round(extract(epoch from (max(at) - min(at))) / 60.0) as run_min
          from (
            select at, inside,
                   count(*) filter (where inside) over (
                     order by at rows between unbounded preceding and 1 preceding) as grp
              from public.presence_pulses p
             where p.staff_id = a.staff_id and p.day = a.day
          ) t
          where not inside
          group by grp
        ) r
      ) st on true
      left join lateral (
        -- longest silence: biggest gap between check-in, pulses and check-out
        select coalesce(max(round(extract(epoch from (at - prev_at)) / 60.0)),0)::integer as silence_min
        from (
          select at, lag(at) over (order by at) as prev_at
          from (
            select a.check_in_at as at where a.check_in_at is not null
            union all
            select p.at from public.presence_pulses p
             where p.staff_id = a.staff_id and p.day = a.day
            union all
            select coalesce(a.check_out_at,
                            case when a.day = ((now() at time zone 'Asia/Kolkata')::date)
                                 then now() end) as at
          ) s where at is not null
        ) g where prev_at is not null
      ) sl on true
      where a.day between v_from and v_to
        and (p_kendra_id is null or a.check_in_kendra_id = p_kendra_id)
        and (coalesce(btrim(p_search),'') = ''
             or s.full_name ilike '%'||p_search||'%'
             or s.mobile_number like '%'||p_search||'%'
             or s.employee_code ilike '%'||p_search||'%')
      order by a.day desc, s.full_name
      limit 2000
    ) t
  ), '[]'::jsonb));
end;
$$;

-- ---------------------------------------------------------------------
-- Grants
-- ---------------------------------------------------------------------
grant execute on function public.login_by_mobile(text) to anon, authenticated;
grant execute on function public.record_check_in(uuid, double precision, double precision) to anon, authenticated;
grant execute on function public.record_pulse(uuid, double precision, double precision) to anon, authenticated;
grant execute on function public.record_check_out(uuid, double precision, double precision, text) to anon, authenticated;
grant execute on function public.get_today(uuid) to anon, authenticated;

grant execute on function public.admin_list_kendras() to authenticated;
grant execute on function public.admin_create_kendra(text, double precision, double precision, integer, integer) to authenticated;
grant execute on function public.admin_update_kendra(uuid, text, double precision, double precision, integer, integer) to authenticated;
grant execute on function public.admin_list_staff() to authenticated;
grant execute on function public.admin_enroll_staff(text, text, text, text, text) to authenticated;
grant execute on function public.admin_staff_kendras(uuid) to authenticated;
grant execute on function public.admin_assign_kendra(uuid, uuid, boolean, time, time) to authenticated;
grant execute on function public.admin_bulk_enroll(jsonb) to authenticated;
grant execute on function public.admin_nodal_visits(date, date, uuid, text) to authenticated;
grant execute on function public.admin_overview() to authenticated;
grant execute on function public.admin_attendance_log(date, date, uuid, text) to authenticated;

commit;

-- ---------------------------------------------------------------------
-- VERIFICATION
-- ---------------------------------------------------------------------
-- staff columns: employee_code + category present, duty_type gone.
select column_name, is_nullable, data_type
  from information_schema.columns
 where table_schema = 'public' and table_name = 'staff'
 order by ordinal_position;

-- kendras: no timing columns left; staff_kendra: timing columns added.
select table_name, column_name
  from information_schema.columns
 where table_schema = 'public'
   and ((table_name = 'kendras'     and column_name in ('check_in_start','check_in_end','day_end','verify_interval_min'))
     or (table_name = 'staff_kendra' and column_name in ('check_in_start','check_in_end','day_end')))
 order by table_name, column_name;

-- visits table exists.
select table_name from information_schema.tables
 where table_schema = 'public' and table_name = 'visits';

-- every staff row has a unique code and a category.
select count(*)                                   as total_staff,
       count(*) filter (where employee_code is null or employee_code = '') as missing_code,
       count(distinct employee_code)              as distinct_codes,
       count(*) filter (where category = 'nodal') as nodal_officers
  from public.staff;

-- assignments inherited the old kendra timings.
select count(*)                                          as total_assignments,
       count(*) filter (where check_in_start is not null) as with_shift
  from public.staff_kendra;

select routine_name from information_schema.routines
 where routine_schema = 'public'
   and routine_name in ('match_allowed_kendra','login_by_mobile','record_check_in',
                        'record_pulse','record_check_out','get_today',
                        'admin_list_kendras','admin_create_kendra','admin_update_kendra',
                        'admin_list_staff','admin_enroll_staff','admin_staff_kendras',
                        'admin_assign_kendra','admin_bulk_enroll','admin_nodal_visits',
                        'admin_overview','admin_attendance_log')
 order by routine_name;

-- should return 0 rows: no admin RPC still takes the old signatures.
select p.proname, pg_get_function_identity_arguments(p.oid) as args
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public'
   and ((p.proname = 'admin_assign_kendra'  and pg_get_function_identity_arguments(p.oid) = 'uuid, uuid, boolean')
     or (p.proname = 'admin_enroll_staff'   and pg_get_function_identity_arguments(p.oid) = 'text, text, text, text')
     or (p.proname in ('admin_create_kendra','admin_update_kendra')
         and pg_get_function_identity_arguments(p.oid) like '%time%'));
