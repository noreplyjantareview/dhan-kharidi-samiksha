-- =====================================================================
-- Migration 6 — strict monitoring
--
--  M1  Day-quality flags: admin_attendance_log() now returns
--      absent_streak_min (longest continuous run of absent pulses) and
--      silence_min (longest gap between check-in / pulses / check-out),
--      plus check_out_reason. The dashboard shows them as badges.
--  M2  Left-area auto check-out: when record_pulse() sees a trailing run
--      of absent pulses spanning >= 30 minutes, it closes the duty with
--      check_out_reason = 'left_area' and work_minutes counted only up
--      to the last verified PRESENT pulse. The day then stays locked.
--      record_check_out() gained p_reason ('manual' / 'day_end').
--
-- Re-runnable. Paste the whole file into the Supabase SQL Editor and Run.
-- =====================================================================

begin;

alter table public.attendance add column if not exists check_out_reason text;

-- ---------------------------------------------------------------------
-- CHECK-OUT with reason
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
-- PULSE: also auto-closes the day after >= 30 min continuous absence
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
-- TODAY snapshot: include the check-out reason
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
-- Attendance log: day-quality flags + check-out reason
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
        a.check_out_reason                                            as check_out_reason,
        coalesce(pc.cnt,0)                                            as present_count,
        coalesce(ac.cnt,0)                                            as absent_count,
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
             or s.mobile_number like '%'||p_search||'%')
      order by a.day desc, s.full_name
      limit 2000
    ) t
  ), '[]'::jsonb));
end;
$$;

grant execute on function public.record_check_out(uuid, double precision, double precision, text)
  to anon, authenticated;

commit;

-- ---------------------------------------------------------------------
-- VERIFICATION
-- ---------------------------------------------------------------------
select column_name from information_schema.columns
 where table_schema = 'public' and table_name = 'attendance'
   and column_name = 'check_out_reason';

select routine_name from information_schema.routines
 where routine_schema = 'public'
   and routine_name in ('record_pulse','record_check_out','get_today','admin_attendance_log')
 order by routine_name;

-- Days already auto-closed by the left-area rule (empty until it first fires):
select day, check_out_reason, work_minutes
  from public.attendance
 where check_out_reason is not null
 order by day desc limit 10;
