-- Migration 10: run after face_auth_v3.sql; re-runnable, single transaction.
-- The on-device engine changed: MediaPipe Face Mesh replaced the OpenCV SFace pipeline. The stored
-- envelope is untouched - version 3, exactly three JSON keys, five unit-norm samples - but the model
-- tag now decides the sample length: a mesh shape print is 39 landmarks x 2 = 78 values, an SFace
-- embedding stays 128.
-- Both shapes stay valid so a phone that has not been upgraded still marks attendance. They are not
-- interchangeable: a mesh build cannot read an SFace print, so version 3 now means "this person needs
-- supervised re-enrolment" and the counts at the end of this file are the size of that queue.
-- Nothing here authorizes a face check by model: enrollment, activation and challenges are unchanged.
begin;
set local search_path = pg_catalog, pg_temp;

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
  v_dimensions integer;
begin
  if p_template is null or octet_length(p_template) > 40000 then return false; end if;
  v_raw := p_template::json;
  if json_typeof(v_raw) is distinct from 'object' then return false; end if;
  select count(*), count(distinct key) into v_keys, v_distinct_keys from json_each(v_raw);
  if v_keys <> 3 or v_distinct_keys <> 3 then return false; end if;
  v_doc := v_raw::jsonb;
  if v_doc->'v' is distinct from '3'::jsonb
     or jsonb_typeof(v_doc->'samples') is distinct from 'array' then
    return false;
  end if;
  -- An unknown, missing or non-string model tag has no length, so it cannot be checked or stored.
  v_dimensions := case v_doc->>'model'
    when 'mesh-print-v1' then 78
    when 'sface-2021dec' then 128
    else null
  end;
  if v_dimensions is null then return false; end if;
  if jsonb_array_length(v_doc->'samples') <> 5 then return false; end if;
  for v_sample in select value from jsonb_array_elements(v_doc->'samples') loop
    if jsonb_typeof(v_sample) is distinct from 'array' then return false; end if;
    if jsonb_array_length(v_sample) <> v_dimensions then return false; end if;
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
  if public._face_template_valid_v3(p_template) then
    -- 4 is the mesh print the current build reads; 3 is the SFace print it cannot, so 3 is the
    -- re-enrolment queue rather than a different spelling of "registered".
    return case when (p_template::jsonb)->>'model' = 'mesh-print-v1' then 4 else 3 end;
  end if;
  if p_template is null or octet_length(p_template) > 40000 then return 0; end if;
  v_doc := p_template::jsonb;
  -- Legacy v2 identification is informational only; it never authorizes a face check.
  if v_doc->'v' = '2'::jsonb and jsonb_typeof(v_doc->'app') = 'string' then return 2; end if;
  return 0;
exception when others then
  return 0;
end;
$$;

-- Same rows and keys as before; only the face_version set that counts as registered widened by one.
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
      'face_registered', coalesce(f.face_version in (3, 4) and not a.enrollment_allowed
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

-- CREATE OR REPLACE keeps an existing function's ACL, but a re-run must be correct on its own.
do $$
declare v_function regprocedure;
begin
  for v_function in
    select p.oid::regprocedure from pg_proc p join pg_namespace n on n.oid = p.pronamespace
    where n.nspname = 'public' and p.proname in (
      '_face_template_valid_v3', '_face_template_version_v3', 'admin_list_staff'
    )
  loop
    execute format('revoke all on function %s from public, anon, authenticated', v_function);
  end loop;
end;
$$;

grant execute on function public.admin_list_staff() to authenticated;

-- Fail the transaction rather than report a silent apply: the two print shapes must be told apart,
-- a length that does not belong to its own model tag must not be storable, and neither helper may
-- be reachable from a client role.
do $$
declare
  v78 jsonb;
  v128 jsonb;
  v_mesh text;
  v_sface text;
begin
  select jsonb_agg(case when x = 1 then 1 else 0 end order by x) into v78 from generate_series(1, 78) x;
  select jsonb_agg(case when x = 1 then 1 else 0 end order by x) into v128 from generate_series(1, 128) x;
  v_mesh := jsonb_build_object('v', 3, 'model', 'mesh-print-v1',
    'samples', jsonb_build_array(v78, v78, v78, v78, v78))::text;
  v_sface := jsonb_build_object('v', 3, 'model', 'sface-2021dec',
    'samples', jsonb_build_array(v128, v128, v128, v128, v128))::text;
  if not public._face_template_valid_v3(v_mesh) then
    raise exception 'migration 10 self-check: valid 5x78 mesh print was rejected';
  end if;
  if public._face_template_version_v3(v_mesh) <> 4 then
    raise exception 'migration 10 self-check: mesh print is not reported as version 4';
  end if;
  if not public._face_template_valid_v3(v_sface) then
    raise exception 'migration 10 self-check: an existing SFace template was rejected';
  end if;
  if public._face_template_version_v3(v_sface) <> 3 then
    raise exception 'migration 10 self-check: SFace print is not reported as version 3';
  end if;
  if public._face_template_valid_v3(jsonb_build_object('v', 3, 'model', 'mesh-print-v1',
       'samples', jsonb_build_array(v128, v128, v128, v128, v128))::text) then
    raise exception 'migration 10 self-check: mesh tag accepted 128-value samples';
  end if;
  if public._face_template_valid_v3(jsonb_build_object('v', 3, 'model', 'sface-2021dec',
       'samples', jsonb_build_array(v78, v78, v78, v78, v78))::text) then
    raise exception 'migration 10 self-check: SFace tag accepted 78-value samples';
  end if;
  if public._face_template_valid_v3(jsonb_build_object('v', 3, 'model', 'face-id-v9',
       'samples', jsonb_build_array(v78, v78, v78, v78, v78))::text) then
    raise exception 'migration 10 self-check: an unknown model tag was accepted';
  end if;
  if exists (
    select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
     where n.nspname = 'public'
       and p.proname in ('_face_template_valid_v3', '_face_template_version_v3')
       and (has_function_privilege('anon', p.oid, 'EXECUTE')
         or has_function_privilege('authenticated', p.oid, 'EXECUTE'))
  ) then
    raise exception 'migration 10 self-check: an internal face helper is client-callable';
  end if;
  if has_function_privilege('anon', 'public.admin_list_staff()', 'EXECUTE') then
    raise exception 'migration 10 self-check: the staff list leaked to the anon role';
  end if;
  if not has_function_privilege('authenticated', 'public.admin_list_staff()', 'EXECUTE') then
    raise exception 'migration 10 self-check: admins lost the staff list';
  end if;
end;
$$;

commit;

-- Read-only verification: every stored shape, both directions of the model/length binding.
-- Writes nothing; run it in the SQL editor and expect the result column to be 'ok' on every row.
with arr(n, v) as (
  select g.n, (select jsonb_agg(case when x = 1 then 1 else 0 end order by x)
                  from generate_series(1, g.n) x)
    from (values (78), (128)) g(n)
),
doc(label, expected_valid, template) as (
  select 'mesh tag with five 78-value samples', true,
         jsonb_build_object('v', 3, 'model', 'mesh-print-v1',
           'samples', jsonb_build_array(a.v, a.v, a.v, a.v, a.v))::text
    from arr a where a.n = 78
  union all
  select 'sface tag with five 128-value samples', true,
         jsonb_build_object('v', 3, 'model', 'sface-2021dec',
           'samples', jsonb_build_array(a.v, a.v, a.v, a.v, a.v))::text
    from arr a where a.n = 128
  union all
  select 'mesh tag with 128-value samples', false,
         jsonb_build_object('v', 3, 'model', 'mesh-print-v1',
           'samples', jsonb_build_array(a.v, a.v, a.v, a.v, a.v))::text
    from arr a where a.n = 128
  union all
  select 'sface tag with 78-value samples', false,
         jsonb_build_object('v', 3, 'model', 'sface-2021dec',
           'samples', jsonb_build_array(a.v, a.v, a.v, a.v, a.v))::text
    from arr a where a.n = 78
  union all
  select 'mesh tag with four samples', false,
         jsonb_build_object('v', 3, 'model', 'mesh-print-v1',
           'samples', jsonb_build_array(a.v, a.v, a.v, a.v))::text
    from arr a where a.n = 78
  union all
  select 'unknown model tag', false,
         jsonb_build_object('v', 3, 'model', 'face-id-v9',
           'samples', jsonb_build_array(a.v, a.v, a.v, a.v, a.v))::text
    from arr a where a.n = 78
  union all
  select 'legacy v2 signature', false, '{"v":2,"app":"fixture"}'
)
select label as check_label, expected_valid as expected,
       public._face_template_valid_v3(template) as actual_valid,
       public._face_template_version_v3(template) as reported_version,
       case when public._face_template_valid_v3(template) is distinct from expected_valid
            then 'FAILED' else 'ok' end as result
  from doc
 order by label;

-- Expected zero rows: no internal face helper or legacy entry point became executable by a client role.
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

-- Expected: admin_list_staff remains callable by authenticated only, and still security definer.
select has_function_privilege('anon', 'public.admin_list_staff()', 'EXECUTE') as anon_execute,
       has_function_privilege('authenticated', 'public.admin_list_staff()', 'EXECUTE') as authenticated_execute,
       prosecdef as security_definer, proconfig as settings
  from pg_proc where oid = 'public.admin_list_staff()'::regprocedure;

-- The re-enrolment queue, counts only: no names, templates or hashes appear here. Every row in the
-- SFace line needs an activation code with the face reset ticked before that person can use the
-- current build again.
select case public._face_template_version_v3(s.face_template)
         when 4 then 'mesh print - current build'
         when 3 then 'SFace print - supervised re-enrolment needed'
         when 2 then 'legacy v2 signature - never usable'
         else 'no readable registration'
       end as registration,
       count(*) as staff,
       count(*) filter (where a.device_token_hash is not null) as activated_phones,
       count(*) filter (where a.enrollment_allowed) as awaiting_registration
  from public.staff s
  left join public.staff_face_access a on a.staff_id = s.id
 group by 1
 order by 1;

