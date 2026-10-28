-- Disposable local PostgreSQL fixture for migration 10 only; never run against a shared database.
-- Needs the migration list (through face_mesh_v4.sql) already applied; it can run after
-- face_auth_v3_tests.sql in the same session, so it uses its own result table and helper.
create temporary table mesh_results (label text not null);
create or replace function pg_temp.check_mesh(p_ok boolean, p_label text)
returns void language plpgsql security definer as $$
begin
  if p_ok is distinct from true then raise exception 'FAILED: %', p_label; end if;
  insert into mesh_results values (p_label);
end;
$$;

insert into auth.users(id) values ('aaaaaaaa-0000-0000-0000-000000000001') on conflict do nothing;
insert into public.admins(auth_id) values ('aaaaaaaa-0000-0000-0000-000000000001') on conflict do nothing;
insert into public.kendras(id, name, latitude, longitude, radius_m) values
  ('22222222-0000-0000-0000-000000000009', 'Mesh Fixture Kendra', 21, 81, 100)
  on conflict (id) do nothing;
insert into public.staff(id, full_name, mobile_number, employee_code, category, face_template) values
  ('33333333-0000-0000-0000-000000000001', 'Mesh Staff', 'mesh-staff', 'MESH-1', 'staff', null),
  ('33333333-0000-0000-0000-000000000002', 'Legacy Print Staff', 'legacy-print', 'MESH-2', 'staff', null)
  on conflict (id) do nothing;
insert into public.staff_kendra(staff_id, kendra_id, check_in_start, check_in_end, day_end) values
  ('33333333-0000-0000-0000-000000000001', '22222222-0000-0000-0000-000000000009',
   '00:00', '23:59:59.999999', '23:59:59.999999'),
  ('33333333-0000-0000-0000-000000000002', '22222222-0000-0000-0000-000000000009',
   '00:00', '23:59:59.999999', '23:59:59.999999')
  on conflict (staff_id, kendra_id) do nothing;

select set_config('request.jwt.claim.sub', 'aaaaaaaa-0000-0000-0000-000000000001', false);

do $$
declare
  s uuid := '33333333-0000-0000-0000-000000000001';
  legacy uuid := '33333333-0000-0000-0000-000000000002';
  kendra uuid := '22222222-0000-0000-0000-000000000009';
  t text := repeat('a1', 32);
  t2 text := repeat('b2', 32);
  v78 jsonb;
  v128 jsonb;
  mesh text;
  sface text;
  r jsonb;
  a jsonb;
  code text;
  cid uuid;
  rev uuid;
begin
  select jsonb_agg(case when x = 1 then 1 else 0 end order by x) into v78 from generate_series(1, 78) x;
  select jsonb_agg(case when x = 1 then 1 else 0 end order by x) into v128 from generate_series(1, 128) x;
  mesh := jsonb_build_object('v', 3, 'model', 'mesh-print-v1',
    'samples', jsonb_build_array(v78, v78, v78, v78, v78))::text;
  sface := jsonb_build_object('v', 3, 'model', 'sface-2021dec',
    'samples', jsonb_build_array(v128, v128, v128, v128, v128))::text;

  perform pg_temp.check_mesh(public._face_template_valid_v3(mesh)
    and public._face_template_version_v3(mesh) = 4, 'mesh print is valid and reported as version 4');
  perform pg_temp.check_mesh(public._face_template_valid_v3(sface)
    and public._face_template_version_v3(sface) = 3,
    'an SFace print stays valid and is reported as the print that needs re-enrolment');

  -- The length is bound to the tag in both directions, and a mesh row that is not unit length is out.
  perform pg_temp.check_mesh(not public._face_template_valid_v3(
    jsonb_build_object('v', 3, 'model', 'mesh-print-v1',
      'samples', jsonb_build_array(v128, v128, v128, v128, v128))::text),
    'mesh tag refuses 128-value samples');
  perform pg_temp.check_mesh(not public._face_template_valid_v3(
    jsonb_build_object('v', 3, 'model', 'sface-2021dec',
      'samples', jsonb_build_array(v78, v78, v78, v78, v78))::text),
    'SFace tag refuses 78-value samples');
  perform pg_temp.check_mesh(not public._face_template_valid_v3(
    jsonb_build_object('v', 3, 'model', 'mesh-print-v1',
      'samples', jsonb_build_array(v78, jsonb_set(v78, '{0}', '0.5'), v78, v78, v78))::text),
    'a mesh sample that is not unit length is refused');
  perform pg_temp.check_mesh(not public._face_template_valid_v3(
    jsonb_build_object('v', 3, 'model', 'mesh-print-v1',
      'samples', jsonb_build_array(v78 - 0, v78, v78, v78, v78))::text),
    'a 77-value mesh sample is refused');
  perform pg_temp.check_mesh(not public._face_template_valid_v3(
    jsonb_build_object('v', 3, 'model', 'mesh-print-v1',
      'samples', jsonb_build_array(v78 || '[0]'::jsonb, v78, v78, v78, v78))::text),
    'a 79-value mesh sample is refused');
  perform pg_temp.check_mesh(not public._face_template_valid_v3(
    jsonb_build_object('v', 3, 'model', 'mesh-print-v2',
      'samples', jsonb_build_array(v78, v78, v78, v78, v78))::text)
    and public._face_template_version_v3(
      jsonb_build_object('v', 3, 'model', 'mesh-print-v2',
        'samples', jsonb_build_array(v78, v78, v78, v78, v78))::text) = 0,
    'a model tag this build has never heard of is refused and reported as version zero');

  -- End to end: this is the only path the rebuilt APK has, so a mesh print has to survive enrollment.
  r := public.login_staff_v3('mesh-staff', t);
  perform pg_temp.check_mesh(r->>'ok' = 'false' and r->>'reason' = 'activation_required',
    'an unactivated phone cannot save a mesh print');
  perform pg_temp.check_mesh(public.save_face_template_v3(s, t, mesh)->>'ok' = 'false',
    'mesh print cannot be saved before activation');
  a := public.admin_issue_face_activation(s);
  code := a->>'activation_code';
  r := public.login_staff_v3('mesh-staff', t, code);
  perform pg_temp.check_mesh(r->>'ok' = 'true' and r#>>'{staff,face_enrollment_allowed}' = 'true',
    'activation authorizes one mesh enrollment');
  perform pg_temp.check_mesh(public.save_face_template_v3(s, t, mesh)->>'ok' = 'true',
    'the rebuilt client can store its mesh print');
  r := public.login_staff_v3('mesh-staff', t);
  perform pg_temp.check_mesh(r#>>'{staff,face_template}' = mesh
    and r#>>'{staff,face_enrollment_allowed}' = 'false',
    'the mesh print is returned to its own activated device');
  rev := (r#>>'{staff,face_revision}')::uuid;
  perform pg_temp.check_mesh(rev is not null, 'mesh enrollment carries a revision for challenges');
  cid := ((public.begin_face_check_v3(s, t, 'on'))->>'challenge_id')::uuid;
  perform pg_temp.check_mesh(cid is not null, 'a mesh registration can start a duty check');
  r := public.record_check_in_v3(s, t, 21, 81, cid);
  perform pg_temp.check_mesh(r->>'ok' = 'true' and r->>'status' in ('checked_in', 'already'),
    'a mesh registration completes an authorized check-in');
  perform pg_temp.check_mesh(public.record_pulse_v3(s, t, 21, 81)->>'ok' = 'true',
    'presence pulses keep delegating for a mesh registration');

  -- The admin list is how the re-enrolment queue gets worked, so both prints have to be told apart.
  r := public.admin_list_staff();
  select value into a from jsonb_array_elements(r->'rows') where value->>'id' = s::text;
  perform pg_temp.check_mesh(a->>'face_version' = '4' and a->>'face_registered' = 'true'
    and a->>'device_activated' = 'true',
    'admin list marks the mesh print registered and current');
  -- An SFace print on an activated, locked device is still usable by the older build that made it...
  update public.staff set face_template = sface where id = legacy;
  insert into public.staff_face_access(staff_id, device_token_hash, template_revision)
    values (legacy, pg_catalog.sha256(decode(t2, 'hex')), gen_random_uuid())
    on conflict (staff_id) do update
      set device_token_hash = excluded.device_token_hash,
          template_revision = excluded.template_revision,
          enrollment_allowed = false;
  r := public.admin_list_staff();
  select value into a from jsonb_array_elements(r->'rows') where value->>'id' = legacy::text;
  perform pg_temp.check_mesh(a->>'face_version' = '3' and a->>'face_registered' = 'true',
    'an SFace print still counts as registered for the build that reads it');
  perform pg_temp.check_mesh(public.record_pulse_v3(legacy, t2, 21, 81)->>'ok' = 'true'
    and public.begin_face_check_v3(legacy, t2, 'on')->>'ok' = 'true',
    'an un-upgraded phone is not blocked by migration 10');

  -- ...but a plain activation keeps it, so the reset flag is the only thing that re-enrols them. This
  -- is the behaviour the dashboard's legacy-print warning has to make visible to the admin.
  a := public.admin_issue_face_activation(legacy);
  code := a->>'activation_code';
  r := public.login_staff_v3('legacy-print', t2, code);
  perform pg_temp.check_mesh(r->>'ok' = 'true' and not (r#>>'{staff,face_enrollment_allowed}')::boolean
    and r#>>'{staff,face_template}' = sface,
    'a plain activation retains the unreadable SFace print instead of opening enrollment');
  a := public.admin_issue_face_activation(legacy, true);
  code := a->>'activation_code';
  r := public.login_staff_v3('legacy-print', t2, code);
  perform pg_temp.check_mesh(r->>'ok' = 'true' and (r#>>'{staff,face_enrollment_allowed}')::boolean
    and r#>'{staff,face_template}' = 'null'::jsonb
    and (select face_template is null from public.staff where id = legacy),
    'an activation with the face reset clears the SFace print and opens mesh enrollment');
  perform pg_temp.check_mesh(public.save_face_template_v3(legacy, t2, mesh)->>'ok' = 'true',
    'the reset path ends in a mesh print');
  r := public.admin_list_staff();
  select value into a from jsonb_array_elements(r->'rows') where value->>'id' = legacy::text;
  perform pg_temp.check_mesh(a->>'face_version' = '4',
    'a re-enrolled person leaves the legacy queue');

  -- Nothing in migration 10 authorizes a check by model alone: a null or corrupt print stays out.
  perform pg_temp.check_mesh(not public._face_template_valid_v3(null)
    and not public._face_template_valid_v3('{"v":3,"model":"mesh-print-v1","samples":null}')
    and public._face_template_version_v3(null) = 0,
    'null and malformed prints stay invalid and unversioned');
  delete from public.attendance where staff_id in (s, legacy);
  delete from public.staff_face_access where staff_id in (s, legacy);
  update public.staff set face_template = null where id in (s, legacy);
  delete from public.staff_kendra where staff_id in (s, legacy);
  delete from public.presence_pulses where staff_id in (s, legacy);
  delete from public.kendras where id = kendra;
end;
$$;

select count(*) as mesh_assertions_passed from mesh_results;
