-- =====================================================================
-- Dhan Kharidi Samiksha — field-staff duty attendance
-- Supabase schema + RPC functions. Single re-runnable migration.
-- Paste the WHOLE file into the Supabase SQL editor and run it.
-- Safe to re-run: it will not drop existing data.
--
-- Domain:
--   kendra        = a duty location / procurement centre (fixed lat-long)
--   staff         = a field employee. role = staff|admin,
--                   duty_type = fixed (one kendra) | multi (several allowed)
--   staff_kendra  = which kendras a staff member may mark attendance from
--   attendance    = one row per staff per IST day (check-in / check-out)
--   presence_pulses = periodic re-verification: present/absent during duty
-- =====================================================================

begin;

create extension if not exists pgcrypto;

-- ---------------------------------------------------------------------
-- Tables
-- ---------------------------------------------------------------------
create table if not exists public.kendras (
  id                uuid primary key default gen_random_uuid(),
  name              text not null,
  latitude          double precision not null,
  longitude         double precision not null,
  radius_m          integer not null default 100,        -- geofence radius (metres)
  check_in_start    time not null default '08:00',       -- IST arrival time
  check_in_end      time not null default '08:30',       -- auto = start + 30 min buffer
  day_end           time not null default '18:00',       -- IST auto check-out deadline
  verify_interval_min integer not null default 30,       -- periodic presence re-check (minutes)
  created_at        timestamptz not null default now()
);

create table if not exists public.staff (
  id            uuid primary key default gen_random_uuid(),
  full_name     text not null,
  mobile_number text not null unique,                     -- login identifier
  role          text not null default 'staff'
                check (role in ('staff','admin')),
  duty_type     text not null default 'fixed'
                check (duty_type in ('fixed','multi')),
  face_template text,                                     -- normalized face geometry, set once
  created_at    timestamptz not null default now()
);

create table if not exists public.staff_kendra (
  staff_id  uuid not null references public.staff(id)   on delete cascade,
  kendra_id uuid not null references public.kendras(id) on delete cascade,
  primary key (staff_id, kendra_id)
);

create table if not exists public.attendance (
  id                 uuid primary key default gen_random_uuid(),
  staff_id           uuid not null references public.staff(id) on delete cascade,
  day                date not null,                       -- IST calendar day
  check_in_at        timestamptz,
  check_in_kendra_id uuid references public.kendras(id),
  check_in_lat       double precision,
  check_in_long      double precision,
  check_out_at       timestamptz,
  check_out_kendra_id uuid references public.kendras(id),
  check_out_lat      double precision,
  check_out_long     double precision,
  status             text not null default 'present'
                     check (status in ('present','late')),
  work_minutes       integer,                             -- total duty duration, set at check-out
  check_out_reason   text                                 -- manual / day_end / left_area
  unique (staff_id, day)
);

create table if not exists public.presence_pulses (
  id         uuid primary key default gen_random_uuid(),
  staff_id   uuid not null references public.staff(id) on delete cascade,
  day        date not null,
  at         timestamptz not null default now(),
  inside     boolean not null,                            -- inside an allowed kendra?
  kendra_id  uuid references public.kendras(id),          -- matched kendra (null if none)
  distance_m double precision,                            -- distance to nearest allowed kendra
  lat        double precision,
  long       double precision
);

create index if not exists idx_attendance_staff_day on public.attendance (staff_id, day desc);
create index if not exists idx_pulses_staff_day     on public.presence_pulses (staff_id, day desc);
create index if not exists idx_staff_kendra_staff   on public.staff_kendra (staff_id);

-- ---------------------------------------------------------------------
-- Row Level Security: lock tables. All access goes through the
-- SECURITY DEFINER functions below (they run as owner, bypass RLS).
-- The anon key therefore has NO direct table access.
-- ---------------------------------------------------------------------
alter table public.kendras         enable row level security;
alter table public.staff           enable row level security;
alter table public.staff_kendra    enable row level security;
alter table public.attendance      enable row level security;
alter table public.presence_pulses enable row level security;

-- ---------------------------------------------------------------------
-- Helpers
-- ---------------------------------------------------------------------
create or replace function public.distance_m(
  lat1 double precision, lon1 double precision,
  lat2 double precision, lon2 double precision
) returns double precision
language sql immutable as $$
  select 6371000 * 2 * asin(sqrt(
    power(sin(radians(lat2 - lat1) / 2), 2) +
    cos(radians(lat1)) * cos(radians(lat2)) *
    power(sin(radians(lon2 - lon1) / 2), 2)
  ));
$$;

-- Nearest allowed kendra for a staff member at a given fix.
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
         k.check_in_start, k.check_in_end, k.day_end, k.verify_interval_min
  from public.staff_kendra sk
  join public.kendras k on k.id = sk.kendra_id
  where sk.staff_id = p_staff_id
  order by public.distance_m(p_lat, p_long, k.latitude, k.longitude) asc
$$;

-- ---------------------------------------------------------------------
-- LOGIN: mobile number only. Returns staff + their allowed kendras.
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
      'mobile_number', v_staff.mobile_number,
      'role', v_staff.role,
      'duty_type', v_staff.duty_type,
      'face_template', v_staff.face_template
    ),
    'kendras', coalesce((
      select jsonb_agg(jsonb_build_object(
               'id', k.id, 'name', k.name,
               'latitude', k.latitude, 'longitude', k.longitude, 'radius_m', k.radius_m,
               'check_in_start', to_char(k.check_in_start,'HH24:MI'),
               'check_in_end',   to_char(k.check_in_end,'HH24:MI'),
               'day_end',        to_char(k.day_end,'HH24:MI'),
               'verify_interval_min', k.verify_interval_min
             ) order by k.name)
      from public.staff_kendra sk join public.kendras k on k.id = sk.kendra_id
      where sk.staff_id = v_staff.id
    ), '[]'::jsonb)
  );
end;
$$;

-- ---------------------------------------------------------------------
-- CHECK-IN: validated server-side against ANY allowed kendra.
-- ---------------------------------------------------------------------
create or replace function public.record_check_in(
  p_staff_id uuid, p_lat double precision, p_long double precision
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_k    record;
  v_now  timestamp := (now() at time zone 'Asia/Kolkata');
  v_date date := ((now() at time zone 'Asia/Kolkata')::date);
  v_att  public.attendance%rowtype;
  v_status text;
begin
  select * into v_k from public.match_allowed_kendra(p_staff_id, p_lat, p_long) limit 1;
  if v_k.kendra_id is null then
    return jsonb_build_object('ok', false, 'error', 'No duty kendra assigned to this staff.');
  end if;

  if not v_k.inside then
    return jsonb_build_object('ok', false, 'reason', 'outside_geofence',
      'error', 'You are outside your allowed duty area.',
      'kendra_name', v_k.kendra_name, 'distance_m', round(v_k.dist_m::numeric,1));
  end if;

  if v_now::time < v_k.win_start or v_now::time > v_k.win_end then
    return jsonb_build_object('ok', false, 'reason', 'outside_window',
      'error', 'Outside the check-in time window.',
      'kendra_name', v_k.kendra_name, 'distance_m', round(v_k.dist_m::numeric,1));
  end if;

  select * into v_att from public.attendance where staff_id = p_staff_id and day = v_date;
  if v_att.id is not null and v_att.check_in_at is not null then
    return jsonb_build_object('ok', true, 'status', 'already', 'message', 'Already checked in for today.',
      'check_in_at', to_char(v_att.check_in_at at time zone 'Asia/Kolkata','HH24:MI:SS'),
      'kendra_id', v_att.check_in_kendra_id, 'distance_m', round(v_k.dist_m::numeric,1));
  end if;

  v_status := case when v_now::time > (v_k.win_start + interval '15 minutes') then 'late' else 'present' end;

  insert into public.attendance (staff_id, day, check_in_at, check_in_kendra_id, check_in_lat, check_in_long, status)
  values (p_staff_id, v_date, now(), v_k.kendra_id, p_lat, p_long, v_status)
  on conflict (staff_id, day) do update
    set check_in_at = excluded.check_in_at,
        check_in_kendra_id = excluded.check_in_kendra_id,
        check_in_lat = excluded.check_in_lat,
        check_in_long = excluded.check_in_long,
        status = excluded.status;

  return jsonb_build_object('ok', true, 'status', 'checked_in', 'message', 'Check-in recorded.',
    'check_in_at', to_char((now() at time zone 'Asia/Kolkata'),'HH24:MI:SS'),
    'attendance_status', v_status,
    'kendra_id', v_k.kendra_id, 'kendra_name', v_k.kendra_name,
    'day_end', to_char(v_k.win_day_end,'HH24:MI'),
    'verify_interval_min', v_k.verify_min,
    'distance_m', round(v_k.dist_m::numeric,1));
end;
$$;

-- ---------------------------------------------------------------------
-- PRESENCE PULSE: periodic re-verification during duty.
-- Always records present/absent so gaps are auditable.
-- ---------------------------------------------------------------------
create or replace function public.record_pulse(
  p_staff_id uuid, p_lat double precision, p_long double precision
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_k        record;
  v_att      public.attendance%rowtype;
  v_date     date := ((now() at time zone 'Asia/Kolkata')::date);
  v_inside   boolean := false;
  v_absent   integer;
  v_present  integer;
  v_rec      record;
  v_run_start timestamptz := null;   -- oldest pulse of the trailing absent run
  v_run_min  integer := 0;
  v_last_present timestamptz;
  v_work     integer;
  v_auto     boolean := false;
begin
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

  -- Left-area auto check-out: a trailing run of absent pulses spanning
  -- >= 30 minutes closes the duty; work time counts verified presence only.
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
-- CHECK-OUT: closes today's check-in (fires at day_end or manual stop).
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
  v_min  integer;
begin
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
-- TODAY snapshot: attendance + pulse tally
-- ---------------------------------------------------------------------
create or replace function public.get_today(p_staff_id uuid)
returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare
  v_date date := ((now() at time zone 'Asia/Kolkata')::date);
  v_att  public.attendance%rowtype;
  v_p    integer; v_a integer; v_last text;
  v_kname text;
begin
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
    'record', case when v_att.id is null then null else jsonb_build_object(
      'status', v_att.status,
      'check_in_at',  to_char(v_att.check_in_at  at time zone 'Asia/Kolkata','HH24:MI:SS'),
      'check_out_at', to_char(v_att.check_out_at at time zone 'Asia/Kolkata','HH24:MI:SS'),
      'check_in_kendra', v_kname,
      'work_minutes', v_att.work_minutes,
      'check_out_reason', v_att.check_out_reason
    ) end,
    'present_count', coalesce(v_p,0),
    'absent_count',  coalesce(v_a,0),
    'last_pulse_at', v_last
  );
end;
$$;

-- ---------------------------------------------------------------------
-- HISTORY
-- ---------------------------------------------------------------------
create or replace function public.get_history(p_staff_id uuid, p_from date, p_to date)
returns jsonb
language plpgsql stable security definer set search_path = public as $$
begin
  return jsonb_build_object('ok', true, 'rows', coalesce((
    select jsonb_agg(jsonb_build_object(
             'day', to_char(a.day,'YYYY-MM-DD'), 'status', a.status,
             'check_in_at',  to_char(a.check_in_at  at time zone 'Asia/Kolkata','HH24:MI:SS'),
             'check_out_at', to_char(a.check_out_at at time zone 'Asia/Kolkata','HH24:MI:SS')
           ) order by a.day desc)
    from public.attendance a
    where a.staff_id = p_staff_id and a.day between p_from and p_to
  ), '[]'::jsonb));
end;
$$;

-- ---------------------------------------------------------------------
-- ADMIN helpers (caller id must be role='admin')
-- ---------------------------------------------------------------------
create or replace function public.admin_list_kendras(p_admin_id uuid)
returns jsonb
language plpgsql stable security definer set search_path = public as $$
begin
  if not exists (select 1 from public.staff where id = p_admin_id and role = 'admin') then
    return jsonb_build_object('ok', false, 'error', 'Not an admin.');
  end if;
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
  p_admin_id uuid, p_name text, p_lat double precision, p_long double precision,
  p_radius_m integer, p_check_in_start time, p_check_in_end time, p_day_end time,
  p_verify_interval_min integer
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare v_id uuid;
begin
  if not exists (select 1 from public.staff where id = p_admin_id and role = 'admin') then
    return jsonb_build_object('ok', false, 'error', 'Not an admin.');
  end if;
  insert into public.kendras
    (name, latitude, longitude, radius_m, check_in_start, check_in_end, day_end, verify_interval_min)
  values
    (p_name, p_lat, p_long, coalesce(p_radius_m,100),
     p_check_in_start, p_check_in_start + interval '30 minutes',
     coalesce(p_day_end,'18:00'), coalesce(p_verify_interval_min,30))
  returning id into v_id;
  return jsonb_build_object('ok', true, 'id', v_id);
end;
$$;

create or replace function public.admin_list_staff(p_admin_id uuid)
returns jsonb
language plpgsql stable security definer set search_path = public as $$
begin
  if not exists (select 1 from public.staff where id = p_admin_id and role = 'admin') then
    return jsonb_build_object('ok', false, 'error', 'Not an admin.');
  end if;
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
  p_admin_id uuid, p_full_name text, p_mobile text, p_role text, p_duty_type text
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare v_id uuid;
begin
  if not exists (select 1 from public.staff where id = p_admin_id and role = 'admin') then
    return jsonb_build_object('ok', false, 'error', 'Not an admin.');
  end if;
  if exists (select 1 from public.staff where mobile_number = btrim(p_mobile)) then
    return jsonb_build_object('ok', false, 'error', 'Mobile number already registered.');
  end if;
  insert into public.staff (full_name, mobile_number, role, duty_type)
  values (p_full_name, btrim(p_mobile),
          coalesce(nullif(btrim(p_role),''),'staff'),
          coalesce(nullif(btrim(p_duty_type),''),'fixed'))
  returning id into v_id;
  return jsonb_build_object('ok', true, 'id', v_id);
end;
$$;

-- Current allowed kendras for a staff member (for the assignment UI).
create or replace function public.admin_staff_kendras(p_admin_id uuid, p_staff_id uuid)
returns jsonb
language plpgsql stable security definer set search_path = public as $$
begin
  if not exists (select 1 from public.staff where id = p_admin_id and role = 'admin') then
    return jsonb_build_object('ok', false, 'error', 'Not an admin.');
  end if;
  return jsonb_build_object('ok', true, 'kendra_ids', coalesce((
    select jsonb_agg(kendra_id) from public.staff_kendra where staff_id = p_staff_id
  ), '[]'::jsonb));
end;
$$;

-- Assign / unassign an allowed kendra.
-- For duty_type='fixed' an assign replaces any existing assignment (exactly one).
create or replace function public.admin_assign_kendra(
  p_admin_id uuid, p_staff_id uuid, p_kendra_id uuid, p_allowed boolean
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare v_duty text;
begin
  if not exists (select 1 from public.staff where id = p_admin_id and role = 'admin') then
    return jsonb_build_object('ok', false, 'error', 'Not an admin.');
  end if;
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

-- Face enrollment: store the normalized face-geometry template ONCE.
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

-- ---------------------------------------------------------------------
-- Grants
-- ---------------------------------------------------------------------
grant execute on function public.login_by_mobile(text) to anon, authenticated;
grant execute on function public.record_check_in(uuid, double precision, double precision) to anon, authenticated;
grant execute on function public.record_pulse(uuid, double precision, double precision) to anon, authenticated;
grant execute on function public.record_check_out(uuid, double precision, double precision) to anon, authenticated;
grant execute on function public.get_today(uuid) to anon, authenticated;
grant execute on function public.get_history(uuid, date, date) to anon, authenticated;
grant execute on function public.save_face_template(uuid, text) to anon, authenticated;
grant execute on function public.admin_list_kendras(uuid) to anon, authenticated;
grant execute on function public.admin_create_kendra(uuid, text, double precision, double precision, integer, time, time, time, integer) to anon, authenticated;
grant execute on function public.admin_list_staff(uuid) to anon, authenticated;
grant execute on function public.admin_enroll_staff(uuid, text, text, text, text) to anon, authenticated;
grant execute on function public.admin_staff_kendras(uuid, uuid) to anon, authenticated;
grant execute on function public.admin_assign_kendra(uuid, uuid, uuid, boolean) to anon, authenticated;

commit;

-- =====================================================================
-- OPTIONAL SEED (re-runnable). Edit to your real values, then run just
-- this block. Creates one kendra + one admin. Log in with that mobile
-- to reach the Admin screen and enroll staff / assign kendras.
-- =====================================================================
-- begin;
-- insert into public.kendras (id, name, latitude, longitude, radius_m, check_in_start, check_in_end, day_end, verify_interval_min)
-- values ('11111111-1111-1111-1111-111111111111', 'Kendra Raipur-1', 21.2514, 81.6296, 150, '08:00', '10:00', '18:00', 30)
-- on conflict (id) do nothing;
--
-- insert into public.staff (id, full_name, mobile_number, role, duty_type)
-- values ('22222222-2222-2222-2222-222222222222', 'District Admin', '9999999999', 'admin', 'fixed')
-- on conflict (id) do nothing;
-- commit;

-- =====================================================================
-- VERIFICATION
-- =====================================================================
select table_name from information_schema.tables
 where table_schema = 'public'
   and table_name in ('kendras','staff','staff_kendra','attendance','presence_pulses')
 order by table_name;

select routine_name from information_schema.routines
 where routine_schema = 'public'
   and routine_name in ('login_by_mobile','record_check_in','record_pulse','record_check_out',
                        'get_today','get_history','match_allowed_kendra',
                        'admin_list_kendras','admin_create_kendra','admin_list_staff',
                        'admin_enroll_staff','admin_staff_kendras','admin_assign_kendra')
 order by routine_name;

select count(*) as seeded_staff   from public.staff;
select count(*) as seeded_kendras from public.kendras;
