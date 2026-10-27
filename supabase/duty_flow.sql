-- =====================================================================
-- Dhan Kharidi Samiksha — duty flow + face verification (migration 4)
-- Run AFTER supabase/kendra_edit.sql. Single re-runnable transaction.
-- Paste the WHOLE file into the Supabase SQL editor and run it.
--
-- Changes:
--   * attendance.work_minutes — total duty duration, stored at check-out.
--   * staff.face_template     — normalized face-geometry template, set ONCE
--                               at the staff's first app login (no photos stored).
--   * save_face_template()    — enroll-once RPC used by the app.
--   * Check-in window closes automatically 30 min after the check-in time
--     (admin picks a single check-in time; p_check_in_end is ignored).
--   * Arrivals more than 15 min after the check-in time are marked 'late'.
--   * get_today / admin_attendance_log / login_by_mobile return the new fields.
-- =====================================================================

begin;

alter table public.attendance add column if not exists work_minutes integer;
alter table public.staff      add column if not exists face_template text;

-- ---------------------------------------------------------------------
-- CHECK-IN: window = [check_in_start, check_in_start + 30 min].
-- Late when arriving more than 15 min after check_in_start.
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
-- CHECK-OUT: closes today's check-in and stores total working minutes.
-- ---------------------------------------------------------------------
create or replace function public.record_check_out(
  p_staff_id uuid, p_lat double precision, p_long double precision
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
      'work_minutes', v_att.work_minutes);
  end if;

  select * into v_k from public.match_allowed_kendra(p_staff_id, p_lat, p_long) limit 1;
  v_min := round(extract(epoch from (now() - v_att.check_in_at)) / 60.0)::integer;

  update public.attendance
     set check_out_at = now(),
         check_out_kendra_id = v_k.kendra_id,
         check_out_lat = p_lat,
         check_out_long = p_long,
         work_minutes = v_min
   where id = v_att.id;

  return jsonb_build_object('ok', true, 'status', 'checked_out', 'message', 'Check-out recorded.',
    'check_out_at', to_char((now() at time zone 'Asia/Kolkata'),'HH24:MI:SS'),
    'work_minutes', v_min);
end;
$$;

-- ---------------------------------------------------------------------
-- TODAY snapshot: attendance + pulse tally + working minutes
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
      'work_minutes', v_att.work_minutes
    ) end,
    'present_count', coalesce(v_p,0),
    'absent_count',  coalesce(v_a,0),
    'last_pulse_at', v_last
  );
end;
$$;

-- ---------------------------------------------------------------------
-- LOGIN: also returns the stored face template (null until enrolled).
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
-- FACE ENROLLMENT: store the normalized face-geometry template ONCE.
-- Later calls are refused so a template can never be silently replaced.
-- ---------------------------------------------------------------------
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
  if exists (select 1 from public.staff where id = p_staff_id and face_template is not null) then
    return jsonb_build_object('ok', false, 'error', 'Face already registered for this user.');
  end if;
  update public.staff set face_template = btrim(p_template) where id = p_staff_id;
  return jsonb_build_object('ok', true);
end;
$$;

-- ---------------------------------------------------------------------
-- KENDRA create/update: check-in window closes 30 min after the
-- check-in time. p_check_in_end is accepted but ignored.
-- ---------------------------------------------------------------------
create or replace function public.admin_create_kendra(
  p_name text, p_lat double precision, p_long double precision, p_radius_m integer,
  p_check_in_start time, p_check_in_end time default null, p_day_end time default null,
  p_verify_interval_min integer default null
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

create or replace function public.admin_update_kendra(
  p_kendra_id uuid,
  p_name text,
  p_latitude double precision,
  p_longitude double precision,
  p_radius_m integer,
  p_check_in_start time,
  p_check_in_end time default null,
  p_day_end time default null,
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
     set name              = btrim(p_name),
         latitude          = p_latitude,
         longitude         = p_longitude,
         radius_m          = coalesce(p_radius_m, radius_m),
         check_in_start    = p_check_in_start,
         check_in_end      = p_check_in_start + interval '30 minutes',
         day_end           = coalesce(p_day_end, day_end),
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
-- Attendance log: include stored working minutes.
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

-- ---------------------------------------------------------------------
-- Grants
-- ---------------------------------------------------------------------
grant execute on function public.save_face_template(uuid, text) to anon, authenticated;

commit;

-- ---------------------------------------------------------------------
-- VERIFICATION
-- ---------------------------------------------------------------------
select table_name, column_name from information_schema.columns
 where table_schema = 'public'
   and ((table_name = 'attendance' and column_name = 'work_minutes')
     or (table_name = 'staff' and column_name = 'face_template'))
 order by table_name, column_name;

select routine_name from information_schema.routines
 where routine_schema = 'public'
   and routine_name in ('save_face_template','record_check_in','record_check_out',
                        'get_today','login_by_mobile','admin_create_kendra',
                        'admin_update_kendra','admin_attendance_log')
 order by routine_name;

-- Every kendra window should now be exactly 30 minutes wide:
select name, check_in_start, check_in_end,
       (check_in_end - check_in_start) = interval '30 minutes' as buffer_ok
from public.kendras order by name;
