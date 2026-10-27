-- Migration 9: run after bulk_kendras.sql; re-runnable, single transaction.
-- PostgreSQL 13+ core SHA-256 / random UUIDs avoid pgcrypto extension-schema assumptions.
-- Client: keep a 32-random-byte hex installation token encrypted across logout; never upload images.
-- Admin activation authorizes supervised enrollment; SFace matching, multi-frame capture and PAD stay on-device.
begin;
set local search_path = pg_catalog, pg_temp;

create table if not exists public.staff_face_access (
  staff_id uuid primary key references public.staff(id) on delete cascade,
  device_token_hash bytea check (octet_length(device_token_hash) = 32),
  activation_code_hash bytea check (octet_length(activation_code_hash) = 32),
  activation_expires_at timestamptz,
  activation_failed_attempts integer not null default 0
    check (activation_failed_attempts between 0 and 5),
  activation_reset_face boolean not null default false,
  activation_admin_id uuid,
  enrollment_allowed boolean not null default false,
  template_revision uuid,
  activated_at timestamptz,
  created_at timestamptz not null default clock_timestamp(),
  updated_at timestamptz not null default clock_timestamp(),
  check ((activation_code_hash is null) = (activation_expires_at is null)),
  check ((activation_code_hash is null) = (activation_admin_id is null)),
  check (not enrollment_allowed or device_token_hash is not null),
  check (not enrollment_allowed or template_revision is null)
);

create table if not exists public.staff_face_challenges (
  staff_id uuid primary key references public.staff_face_access(staff_id) on delete cascade,
  challenge_id uuid not null unique,
  device_token_hash bytea not null check (octet_length(device_token_hash) = 32),
  action text not null check (action in ('on', 'off')),
  template_revision uuid not null,
  turn text not null check (turn in ('left', 'right')),
  expires_at timestamptz not null
);

-- Audit contains only admin attribution, action and timestamp; no credentials or biometrics.
create table if not exists public.staff_face_activation_audit (
  admin_id uuid not null,
  action text not null check (action in (
    'issue_activation', 'issue_reset_activation', 'activate_device', 'activate_device_reset'
  )),
  created_at timestamptz not null default clock_timestamp()
);

alter table public.staff_face_access enable row level security;
alter table public.staff_face_challenges enable row level security;
alter table public.staff_face_activation_audit enable row level security;
revoke all on table public.staff_face_access, public.staff_face_challenges,
  public.staff_face_activation_audit from public, anon, authenticated;

create or replace function public._face_device_hash_v3(p_token text)
returns bytea
language sql immutable set search_path = pg_catalog, pg_temp as $$
  select case when octet_length(p_token) = 64 and p_token ~ '^[0-9A-Fa-f]{64}$'
    then sha256(decode(p_token, 'hex')) else null end
$$;

create or replace function public._face_template_valid_v3(p_template text)
returns boolean
language plpgsql immutable set search_path = pg_catalog, pg_temp as $$
declare
  v_raw json;
  v_doc jsonb;
  v_sample jsonb;
  v_component jsonb;
  v_keys integer;
  v_distinct_keys integer;
  v_value double precision;
  v_squared_norm double precision;
begin
  if p_template is null or octet_length(p_template) > 40000 then return false; end if;
  v_raw := p_template::json;
  if json_typeof(v_raw) is distinct from 'object' then return false; end if;
  select count(*), count(distinct key) into v_keys, v_distinct_keys from json_each(v_raw);
  if v_keys <> 3 or v_distinct_keys <> 3 then return false; end if;
  v_doc := v_raw::jsonb;
  if v_doc->'v' is distinct from '3'::jsonb
     or v_doc->'model' is distinct from '"sface-2021dec"'::jsonb
     or jsonb_typeof(v_doc->'samples') is distinct from 'array' then
    return false;
  end if;
  if jsonb_array_length(v_doc->'samples') <> 5 then return false; end if;
  for v_sample in select value from jsonb_array_elements(v_doc->'samples') loop
    if jsonb_typeof(v_sample) is distinct from 'array' then return false; end if;
    if jsonb_array_length(v_sample) <> 128 then return false; end if;
    v_squared_norm := 0;
    for v_component in select value from jsonb_array_elements(v_sample) loop
      if jsonb_typeof(v_component) is distinct from 'number' then return false; end if;
      v_value := v_component::text::double precision;
      -- Bounds reject NaN/infinities and prevent overflow before squaring.
      if not (v_value between -1.02 and 1.02) then return false; end if;
      v_squared_norm := v_squared_norm + v_value * v_value;
    end loop;
    if not (sqrt(v_squared_norm) between 0.98 and 1.02) then return false; end if;
  end loop;
  return true;
exception when others then
  return false;
end;
$$;

create or replace function public._face_template_version_v3(p_template text)
returns integer
language plpgsql immutable set search_path = pg_catalog, pg_temp as $$
declare v_doc jsonb;
begin
  if public._face_template_valid_v3(p_template) then return 3; end if;
  if p_template is null or octet_length(p_template) > 40000 then return 0; end if;
  v_doc := p_template::jsonb;
  -- Legacy v2 identification is informational only; it never authorizes a face check.
  if v_doc->'v' = '2'::jsonb and jsonb_typeof(v_doc->'app') = 'string' then return 2; end if;
  return 0;
exception when others then
  return 0;
end;
$$;

-- All device operations hold this row lock through delegation, serializing token rotation.
create or replace function public._face_lock_device_v3(p_staff_id uuid, p_device_token text)
returns public.staff_face_access
language plpgsql set search_path = pg_catalog, pg_temp as $$
declare
  v_access public.staff_face_access%rowtype;
  v_hash bytea := public._face_device_hash_v3(p_device_token);
begin
  if v_hash is null then return null; end if;
  select * into v_access from public.staff_face_access where staff_id = p_staff_id for update;
  if v_access.device_token_hash is null or v_access.device_token_hash <> v_hash then
    return null;
  end if;
  return v_access;
end;
$$;

create or replace function public._face_enrolled_v3(p_access public.staff_face_access)
returns boolean
language sql stable set search_path = pg_catalog, pg_temp as $$
  select coalesce(
    p_access.device_token_hash is not null and not p_access.enrollment_allowed
    and p_access.template_revision is not null
    and (select public._face_template_valid_v3(s.face_template)
           from public.staff s where s.id = p_access.staff_id), false)
$$;

-- Call only with the access row locked; a rejected downstream punch must not resurrect this nonce.
create or replace function public._face_consume_challenge_v3(
  p_staff_id uuid, p_device_hash bytea, p_challenge_id uuid, p_action text, p_revision uuid
) returns boolean
language plpgsql set search_path = pg_catalog, pg_temp as $$
begin
  delete from public.staff_face_challenges
   where staff_id = p_staff_id and challenge_id = p_challenge_id
     and device_token_hash = p_device_hash and action = p_action
     and template_revision = p_revision and expires_at > clock_timestamp();
  return found;
end;
$$;

create or replace function public.admin_issue_face_activation(
  p_staff_id uuid, p_reset_face boolean default false
) returns jsonb
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare
  v_code text;
  v_expires timestamptz;
begin
  perform public.assert_admin();
  if not exists (select 1 from public.staff where id = p_staff_id) then
    return jsonb_build_object('ok', false, 'error', 'Request denied.');
  end if;
  insert into public.staff_face_access (staff_id) values (p_staff_id) on conflict do nothing;
  perform 1 from public.staff_face_access where staff_id = p_staff_id for update;
  -- The first 12 UUID hex digits are 48 random bits, excluding the UUID version/variant bits.
  v_code := upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 12));
  v_expires := clock_timestamp() + interval '15 minutes';
  update public.staff_face_access
     set activation_code_hash = sha256(convert_to('activation:' || p_staff_id::text || ':' || v_code, 'UTF8')),
         activation_expires_at = v_expires, activation_failed_attempts = 0,
         activation_reset_face = coalesce(p_reset_face, false), activation_admin_id = auth.uid(),
         updated_at = clock_timestamp()
   where staff_id = p_staff_id;
  insert into public.staff_face_activation_audit (admin_id, action)
  values (auth.uid(), case when coalesce(p_reset_face, false)
    then 'issue_reset_activation' else 'issue_activation' end);
  return jsonb_build_object('ok', true, 'activation_code', v_code, 'expires_at', v_expires);
exception when others then
  return jsonb_build_object('ok', false, 'error', 'Request denied.');
end;
$$;

create or replace function public.login_staff_v3(
  p_mobile text, p_device_token text, p_activation_code text default null
) returns jsonb
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare
  v_staff_id uuid;
  v_access public.staff_face_access%rowtype;
  v_hash bytea := public._face_device_hash_v3(p_device_token);
  v_code text;
  v_template text;
  v_keep_face boolean;
  v_payload jsonb;
  v_reason text := case when p_activation_code is null then 'activation_required' else 'invalid_activation' end;
begin
  if v_hash is null then
    return jsonb_build_object('ok', false, 'error', 'Request denied.', 'reason', v_reason);
  end if;
  select id into v_staff_id from public.staff where mobile_number = btrim(p_mobile);
  select * into v_access from public.staff_face_access where staff_id = v_staff_id for update;
  if v_access.staff_id is null then
    return jsonb_build_object('ok', false, 'error', 'Request denied.', 'reason', v_reason);
  end if;

  -- Supplying a code explicitly also permits an admin-approved reset on the same installation.
  if p_activation_code is not null or v_access.device_token_hash is distinct from v_hash then
    if p_activation_code is null then
      return jsonb_build_object('ok', false, 'error', 'Request denied.', 'reason', 'activation_required');
    end if;
    if v_access.activation_code_hash is null
       or v_access.activation_expires_at <= clock_timestamp()
       or v_access.activation_failed_attempts >= 5 then
      return jsonb_build_object('ok', false, 'error', 'Request denied.', 'reason', 'invalid_activation');
    end if;
    v_code := case when octet_length(p_activation_code) <= 128 then upper(btrim(p_activation_code)) end;
    if v_code is null or v_code !~ '^[0-9A-F]{12}$'
       or sha256(convert_to('activation:' || v_staff_id::text || ':' || v_code, 'UTF8'))
          is distinct from v_access.activation_code_hash then
      update public.staff_face_access
         set activation_failed_attempts = activation_failed_attempts + 1, updated_at = clock_timestamp()
       where staff_id = v_staff_id;
      -- Expected denial returns normally so the failed-attempt increment commits.
      return jsonb_build_object('ok', false, 'error', 'Request denied.', 'reason', 'invalid_activation');
    end if;

    select face_template into v_template from public.staff where id = v_staff_id for update;
    v_keep_face := not v_access.activation_reset_face and public._face_template_valid_v3(v_template);
    if not v_keep_face then
      update public.staff set face_template = null where id = v_staff_id;
    end if;
    delete from public.staff_face_challenges where staff_id = v_staff_id;
    insert into public.staff_face_activation_audit (admin_id, action)
    values (v_access.activation_admin_id, case when v_access.activation_reset_face
      then 'activate_device_reset' else 'activate_device' end);
    update public.staff_face_access
       set device_token_hash = v_hash, activated_at = clock_timestamp(), updated_at = clock_timestamp(),
           enrollment_allowed = not v_keep_face,
           template_revision = case when v_keep_face then coalesce(template_revision, gen_random_uuid()) else null end,
           activation_code_hash = null, activation_expires_at = null, activation_failed_attempts = 0,
           activation_reset_face = false, activation_admin_id = null
     where staff_id = v_staff_id returning * into v_access;
  end if;

  v_payload := public.login_by_mobile(p_mobile => p_mobile);
  v_payload := jsonb_set(v_payload, '{staff}', (v_payload->'staff') || jsonb_build_object(
    'face_enrollment_allowed', v_access.enrollment_allowed,
    'face_revision', v_access.template_revision));
  if not public._face_enrolled_v3(v_access) then
    v_payload := jsonb_set(v_payload, '{staff,face_template}', 'null'::jsonb);
  end if;
  return v_payload;
exception when others then
  return jsonb_build_object('ok', false, 'error', 'Request denied.', 'reason', v_reason);
end;
$$;

create or replace function public.save_face_template_v3(
  p_staff_id uuid, p_device_token text, p_template text
) returns jsonb
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare
  v_access public.staff_face_access%rowtype;
  v_revision uuid;
begin
  v_access := public._face_lock_device_v3(p_staff_id, p_device_token);
  if v_access.staff_id is null or not v_access.enrollment_allowed then
    return jsonb_build_object('ok', false, 'error', 'Request denied.');
  end if;
  if not public._face_template_valid_v3(p_template) then
    return jsonb_build_object('ok', false, 'error', 'Invalid face template.');
  end if;
  v_revision := gen_random_uuid();
  update public.staff set face_template = btrim(p_template) where id = p_staff_id;
  update public.staff_face_access
     set enrollment_allowed = false, template_revision = v_revision, updated_at = clock_timestamp()
   where staff_id = p_staff_id;
  delete from public.staff_face_challenges where staff_id = p_staff_id;
  return jsonb_build_object('ok', true, 'face_revision', v_revision);
exception when others then
  return jsonb_build_object('ok', false, 'error', 'Request denied.');
end;
$$;

create or replace function public.begin_face_check_v3(
  p_staff_id uuid, p_device_token text, p_action text
) returns jsonb
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare
  v_access public.staff_face_access%rowtype;
  v_challenge uuid;
  v_turn text;
begin
  v_access := public._face_lock_device_v3(p_staff_id, p_device_token);
  if v_access.staff_id is null or not public._face_enrolled_v3(v_access)
     or p_action is null or p_action not in ('on', 'off') then
    return jsonb_build_object('ok', false, 'error', 'Request denied.');
  end if;
  v_challenge := gen_random_uuid();
  v_turn := case when get_byte(uuid_send(gen_random_uuid()), 0) % 2 = 0 then 'left' else 'right' end;
  -- A challenge and device token restrict requests, but do not prove actual liveness.
  insert into public.staff_face_challenges
    (staff_id, challenge_id, device_token_hash, action, template_revision, turn, expires_at)
  values (p_staff_id, v_challenge, v_access.device_token_hash, p_action,
    v_access.template_revision, v_turn, clock_timestamp() + interval '90 seconds')
  on conflict (staff_id) do update
    set challenge_id = excluded.challenge_id, device_token_hash = excluded.device_token_hash,
        action = excluded.action, template_revision = excluded.template_revision,
        turn = excluded.turn, expires_at = excluded.expires_at;
  return jsonb_build_object('ok', true, 'challenge_id', v_challenge, 'turn', v_turn,
    'expires_in', 90, 'face_revision', v_access.template_revision);
exception when others then
  return jsonb_build_object('ok', false, 'error', 'Request denied.');
end;
$$;

create or replace function public.record_check_in_v3(
  p_staff_id uuid, p_device_token text, p_lat double precision, p_long double precision,
  p_challenge_id uuid
) returns jsonb
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v_access public.staff_face_access%rowtype;
begin
  v_access := public._face_lock_device_v3(p_staff_id, p_device_token);
  if v_access.staff_id is null or not public._face_enrolled_v3(v_access) then
    return jsonb_build_object('ok', false, 'error', 'Request denied.');
  end if;
  if not public._face_consume_challenge_v3(p_staff_id, v_access.device_token_hash,
      p_challenge_id, 'on', v_access.template_revision) then
    return jsonb_build_object('ok', false, 'error', 'Request denied.');
  end if;
  -- Catch downstream exceptions inside the consumption boundary, preserving the deletion.
  begin
    if p_lat is null or p_long is null or not (p_lat between -90 and 90)
       or not (p_long between -180 and 180) then
      return jsonb_build_object('ok', false, 'error', 'Invalid coordinates.');
    end if;
    return public.record_check_in(p_staff_id => p_staff_id, p_lat => p_lat, p_long => p_long);
  exception when others then
    return jsonb_build_object('ok', false, 'error', 'Request denied.');
  end;
exception when others then
  return jsonb_build_object('ok', false, 'error', 'Request denied.');
end;
$$;

create or replace function public.record_check_out_v3(
  p_staff_id uuid, p_device_token text, p_lat double precision, p_long double precision,
  p_reason text default 'manual', p_challenge_id uuid default null
) returns jsonb
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare
  v_access public.staff_face_access%rowtype;
  v_shift record;
  v_shift_count integer := 0;
  v_ist timestamp;
  v_day_end time;
  v_start time;
begin
  v_access := public._face_lock_device_v3(p_staff_id, p_device_token);
  if v_access.staff_id is null or not public._face_enrolled_v3(v_access)
     or p_reason is null or p_reason not in ('manual', 'day_end') then
    return jsonb_build_object('ok', false, 'error', 'Request denied.');
  end if;
  if p_reason = 'manual' or p_challenge_id is not null then
    if not public._face_consume_challenge_v3(p_staff_id, v_access.device_token_hash,
        p_challenge_id, 'off', v_access.template_revision) then
      return jsonb_build_object('ok', false, 'error', 'Request denied.');
    end if;
  end if;
  begin
    if p_lat is null or p_long is null or not (p_lat between -90 and 90)
       or not (p_long between -180 and 180) then
      return jsonb_build_object('ok', false, 'error', 'Invalid coordinates.');
    end if;
    if p_reason = 'day_end' then
      -- Use the current assignment, never phone time or the nearest GPS kendra.
      for v_shift in
        select sk.check_in_start, sk.day_end
          from public.staff_kendra sk join public.staff s on s.id = sk.staff_id
         where sk.staff_id = p_staff_id and s.category = 'staff'
         for share of sk, s
      loop
        v_shift_count := v_shift_count + 1;
        v_start := v_shift.check_in_start;
        v_day_end := v_shift.day_end;
      end loop;
      v_ist := clock_timestamp() at time zone 'Asia/Kolkata';
      -- The original attendance delegate is IST-calendar-day based, not overnight-shift aware.
      if v_shift_count <> 1 or v_start is null or v_day_end is null
         or v_day_end <= v_start or v_ist::time < v_day_end then
        return jsonb_build_object('ok', false, 'error', 'Request denied.');
      end if;
    end if;
    return public.record_check_out(p_staff_id => p_staff_id, p_lat => p_lat,
      p_long => p_long, p_reason => p_reason);
  exception when others then
    return jsonb_build_object('ok', false, 'error', 'Request denied.');
  end;
exception when others then
  return jsonb_build_object('ok', false, 'error', 'Request denied.');
end;
$$;

create or replace function public.record_pulse_v3(
  p_staff_id uuid, p_device_token text, p_lat double precision, p_long double precision
) returns jsonb
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v_access public.staff_face_access%rowtype;
begin
  v_access := public._face_lock_device_v3(p_staff_id, p_device_token);
  if v_access.staff_id is null or not public._face_enrolled_v3(v_access) then
    return jsonb_build_object('ok', false, 'error', 'Request denied.');
  end if;
  if p_lat is null or p_long is null or not (p_lat between -90 and 90)
     or not (p_long between -180 and 180) then
    return jsonb_build_object('ok', false, 'error', 'Invalid coordinates.');
  end if;
  return public.record_pulse(p_staff_id => p_staff_id, p_lat => p_lat, p_long => p_long);
exception when others then
  return jsonb_build_object('ok', false, 'error', 'Request denied.');
end;
$$;

-- Snapshots work before enrollment, but still require the activated installation token.
create or replace function public.get_today_v3(p_staff_id uuid, p_device_token text)
returns jsonb
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v_access public.staff_face_access%rowtype;
begin
  v_access := public._face_lock_device_v3(p_staff_id, p_device_token);
  if v_access.staff_id is null then
    return jsonb_build_object('ok', false, 'error', 'Request denied.');
  end if;
  return public.get_today(p_staff_id => p_staff_id);
exception when others then
  return jsonb_build_object('ok', false, 'error', 'Request denied.');
end;
$$;

create or replace function public.get_history_v3(
  p_staff_id uuid, p_device_token text, p_from date, p_to date
) returns jsonb
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v_access public.staff_face_access%rowtype;
begin
  v_access := public._face_lock_device_v3(p_staff_id, p_device_token);
  if v_access.staff_id is null then
    return jsonb_build_object('ok', false, 'error', 'Request denied.');
  end if;
  return public.get_history(p_staff_id => p_staff_id, p_from => p_from, p_to => p_to);
exception when others then
  return jsonb_build_object('ok', false, 'error', 'Request denied.');
end;
$$;

create or replace function public.admin_list_staff()
returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, pg_temp as $$
begin
  perform public.assert_admin();
  return jsonb_build_object('ok', true, 'rows', coalesce((
    select jsonb_agg(jsonb_build_object(
      'id', s.id, 'full_name', s.full_name,
      'employee_code', s.employee_code, 'mobile_number', s.mobile_number,
      'role', s.role, 'category', s.category,
      'face_registered', coalesce(f.face_version = 3 and not a.enrollment_allowed
        and a.template_revision is not null and a.device_token_hash is not null, false),
      'face_version', f.face_version,
      'device_activated', (a.device_token_hash is not null),
      'kendra_names', (select string_agg(
        k.name || coalesce(' (' || to_char(sk.check_in_start, 'HH24:MI') || ')', ''), ', ' order by k.name)
        from public.staff_kendra sk join public.kendras k on k.id = sk.kendra_id
        where sk.staff_id = s.id)
    ) order by s.full_name)
    from public.staff s left join public.staff_face_access a on a.staff_id = s.id
    cross join lateral (select public._face_template_version_v3(s.face_template) as face_version) f
  ), '[]'::jsonb));
end;
$$;

-- Revoke all overloads of exactly these legacy entry points, including any old 3-argument checkout.
do $$
declare v_function regprocedure;
begin
  for v_function in
    select p.oid::regprocedure from pg_proc p join pg_namespace n on n.oid = p.pronamespace
    where n.nspname = 'public' and p.proname in (
      'login_by_mobile', 'save_face_template', 'record_check_in', 'record_check_out',
      'record_pulse', 'get_today', 'get_history', 'match_allowed_kendra',
      '_face_device_hash_v3', '_face_template_valid_v3', '_face_template_version_v3',
      '_face_lock_device_v3', '_face_enrolled_v3', '_face_consume_challenge_v3',
      'admin_issue_face_activation', 'admin_list_staff', 'login_staff_v3', 'save_face_template_v3',
      'begin_face_check_v3', 'record_check_in_v3', 'record_check_out_v3',
      'record_pulse_v3', 'get_today_v3', 'get_history_v3'
    )
  loop
    execute format('revoke execute on function %s from public, anon, authenticated', v_function);
  end loop;
end;
$$;

grant execute on function public.admin_issue_face_activation(uuid, boolean), public.admin_list_staff()
  to authenticated;
grant execute on function public.login_staff_v3(text, text, text),
  public.save_face_template_v3(uuid, text, text), public.begin_face_check_v3(uuid, text, text),
  public.record_check_in_v3(uuid, text, double precision, double precision, uuid),
  public.record_check_out_v3(uuid, text, double precision, double precision, text, uuid),
  public.record_pulse_v3(uuid, text, double precision, double precision),
  public.get_today_v3(uuid, text), public.get_history_v3(uuid, text, date, date)
  to anon, authenticated;

commit;

-- Read-only verification: signatures, defaults, security and client execution rights (no RPC calls).
select p.oid::regprocedure as rpc, pg_get_function_arguments(p.oid) as arguments,
       p.prosecdef as security_definer, p.proconfig as settings,
       has_function_privilege('anon', p.oid, 'EXECUTE') as anon_execute,
       has_function_privilege('authenticated', p.oid, 'EXECUTE') as authenticated_execute
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public' and p.proname in (
   'admin_issue_face_activation', 'admin_list_staff', 'login_staff_v3', 'save_face_template_v3',
   'begin_face_check_v3', 'record_check_in_v3', 'record_check_out_v3', 'record_pulse_v3',
   'get_today_v3', 'get_history_v3')
 order by p.proname;

-- Expected zero rows: no legacy RPC / internal helper remains executable by either client role or PUBLIC.
select p.oid::regprocedure as unexpectedly_exposed
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public' and p.proname in (
   'login_by_mobile', 'save_face_template', 'record_check_in', 'record_check_out', 'record_pulse',
   'get_today', 'get_history', 'match_allowed_kendra', '_face_device_hash_v3',
   '_face_template_valid_v3', '_face_template_version_v3', '_face_lock_device_v3',
   '_face_enrolled_v3', '_face_consume_challenge_v3')
   and (has_function_privilege('anon', p.oid, 'EXECUTE')
     or has_function_privilege('authenticated', p.oid, 'EXECUTE')
     or exists (select 1 from aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) acl
                where acl.grantee = 0 and acl.privilege_type = 'EXECUTE'));

-- Expected RLS true, client table access false, and zero policies.
select c.relname, c.relrowsecurity as rls_enabled,
       has_table_privilege('anon', c.oid, 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') as anon_access,
       has_table_privilege('authenticated', c.oid, 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') as authenticated_access,
       (select count(*) from pg_policy p where p.polrelid = c.oid) as policies
  from pg_class c join pg_namespace n on n.oid = c.relnamespace
 where n.nspname = 'public'
   and c.relname in ('staff_face_access', 'staff_face_challenges', 'staff_face_activation_audit')
 order by c.relname;

-- Counts only: do not expose activation codes, token hashes, templates or staff details in handover.
select count(*) as access_rows,
       count(*) filter (where device_token_hash is not null) as activated_devices,
       count(*) filter (where enrollment_allowed) as awaiting_enrollment,
       count(*) filter (where activation_code_hash is not null and activation_failed_attempts = 5) as locked_codes
  from public.staff_face_access;

-- Expected zero: stale challenges bound to a superseded device/revision or an unlocked enrollment.
select count(*) as invalid_challenge_bindings
  from public.staff_face_challenges c join public.staff_face_access a using (staff_id)
 where c.device_token_hash is distinct from a.device_token_hash
    or c.template_revision is distinct from a.template_revision or a.enrollment_allowed;
