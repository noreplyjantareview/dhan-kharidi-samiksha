-- Disposable local PostgreSQL fixture only; never run against a shared database.
create temporary table test_results (label text not null);
create or replace function pg_temp.check_true(p_ok boolean, p_label text)
returns void language plpgsql security definer as $$
begin
  if p_ok is distinct from true then raise exception 'FAILED: %', p_label; end if;
  insert into test_results values (p_label);
end;
$$;

insert into auth.users(id) values ('aaaaaaaa-0000-0000-0000-000000000001');
insert into public.admins(auth_id) values ('aaaaaaaa-0000-0000-0000-000000000001');
insert into public.staff(id, full_name, mobile_number, employee_code, category, face_template) values
  ('11111111-0000-0000-0000-000000000001', 'Test Staff', 'fixture-staff', 'TEST-1', 'staff', '{"v":2,"app":"fixture"}'),
  ('11111111-0000-0000-0000-000000000002', 'Test Nodal', 'fixture-nodal', 'TEST-2', 'nodal', null),
  ('11111111-0000-0000-0000-000000000003', 'Test Legacy', 'fixture-legacy', 'TEST-3', 'staff', '{"v":1}');
insert into public.kendras(id,name,latitude,longitude,radius_m) values
  ('22222222-0000-0000-0000-000000000001','Fixture Kendra',21,81,100),
  ('22222222-0000-0000-0000-000000000002','Fixture Other',22,82,100);
insert into public.staff_kendra(staff_id,kendra_id,check_in_start,check_in_end,day_end) values
  ('11111111-0000-0000-0000-000000000001','22222222-0000-0000-0000-000000000001','00:00','23:59:59.999999','23:59:59.999999'),
  ('11111111-0000-0000-0000-000000000002','22222222-0000-0000-0000-000000000001',null,null,null);

select pg_temp.check_true(not exists (
  select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
   where n.nspname = 'public' and p.proname in (
     'login_by_mobile','save_face_template','record_check_in','record_check_out','record_pulse',
     'get_today','get_history','match_allowed_kendra','_face_device_hash_v3','_face_template_valid_v3',
     '_face_template_version_v3','_face_lock_device_v3','_face_enrolled_v3','_face_consume_challenge_v3')
     and (has_function_privilege('anon',p.oid,'EXECUTE') or has_function_privilege('authenticated',p.oid,'EXECUTE'))
), 'all legacy overloads and internal helpers denied to both client roles');
select pg_temp.check_true(not exists (
  select 1 from pg_class c join pg_namespace n on n.oid = c.relnamespace
   where n.nspname = 'public' and c.relname in ('staff_face_access','staff_face_challenges','staff_face_activation_audit')
     and (not c.relrowsecurity or has_table_privilege('anon',c.oid,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
       or has_table_privilege('authenticated',c.oid,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER'))
), 'new tables have RLS and no direct client privileges');
select pg_temp.check_true(not exists (
  select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
   where n.nspname = 'public' and p.proname in ('login_staff_v3','save_face_template_v3','begin_face_check_v3',
     'record_check_in_v3','record_check_out_v3','record_pulse_v3','get_today_v3','get_history_v3')
     and (not p.prosecdef or not has_function_privilege('anon',p.oid,'EXECUTE')
       or not has_function_privilege('authenticated',p.oid,'EXECUTE')
       or not ('search_path=pg_catalog, pg_temp' = any(p.proconfig)))
), 'all eight device APIs are callable with pinned definer search paths');
select pg_temp.check_true(not has_function_privilege('anon','public.admin_issue_face_activation(uuid,boolean)','EXECUTE')
  and has_function_privilege('authenticated','public.admin_issue_face_activation(uuid,boolean)','EXECUTE'), 'activation issuance is authenticated-only');
select pg_temp.check_true((public.admin_issue_face_activation('11111111-0000-0000-0000-000000000001')->>'ok')::boolean = false,
  'admin issuance denies missing admin JWT');
select set_config('request.jwt.claim.sub','aaaaaaaa-0000-0000-0000-000000000001',false);

do $$
declare
  s uuid := '11111111-0000-0000-0000-000000000001';
  n uuid := '11111111-0000-0000-0000-000000000002';
  t text := repeat('ab',32);
  t2 text := repeat('cd',32);
  t3 text := repeat('ef',32);
  v_sample jsonb;
  v_doc jsonb;
  v_bad text;
  v_template text;
  r jsonb;
  a jsonb;
  c jsonb;
  code text;
  old_code text;
  cid uuid;
  old_cid uuid;
  rev uuid;
  old_rev uuid;
  original_definition text;
  snapshot jsonb;
  i integer;
begin
  select jsonb_agg(case when x = 1 then 1 else 0 end order by x) into v_sample from generate_series(1,128) x;
  v_doc := jsonb_build_object('v',3,'model','sface-2021dec','samples',jsonb_build_array(v_sample,v_sample,v_sample,v_sample,v_sample));
  v_template := v_doc::text;
  perform pg_temp.check_true(public._face_template_valid_v3(v_template), 'valid 5x128 unit-norm SFace template accepted');
  perform pg_temp.check_true(public._face_template_valid_v3(jsonb_set(v_doc,'{samples,0,0}','0.98')::text), 'norm lower boundary accepted');
  perform pg_temp.check_true(public._face_template_valid_v3(jsonb_set(v_doc,'{samples,0,0}','1.02')::text), 'norm upper boundary accepted');
  for v_bad in select unnest(array[
    null, '', 'null', '[]', '{', '{"v":2,"app":"fixture"}', '{"v":1}',
    jsonb_set(v_doc,'{v}','"3"')::text,
    jsonb_set(v_doc,'{model}','"other"')::text,
    (v_doc || '{"image":"forbidden"}'::jsonb)::text,
    (v_doc - 'model')::text,
    jsonb_set(v_doc,'{samples}','[]')::text,
    jsonb_set(v_doc,'{samples}',(v_doc->'samples') - 0)::text,
    jsonb_set(v_doc,'{samples}',(v_doc->'samples') || jsonb_build_array(v_sample))::text,
    jsonb_set(v_doc,'{samples,0}',(v_sample - 0))::text,
    jsonb_set(v_doc,'{samples,0}',(v_sample || '[0]'))::text,
    jsonb_set(v_doc,'{samples,0}','null')::text,
    jsonb_set(v_doc,'{samples,0,0}','null')::text,
    jsonb_set(v_doc,'{samples,0,0}','true')::text,
    jsonb_set(v_doc,'{samples,0,0}','"1"')::text,
    jsonb_set(v_doc,'{samples,0,0}','"NaN"')::text,
    jsonb_set(v_doc,'{samples,0,0}','0.979')::text,
    jsonb_set(v_doc,'{samples,0,0}','1.021')::text,
    jsonb_set(v_doc,'{samples,0,0}','0')::text,
    jsonb_set(v_doc,'{samples,0,0}','1e1000')::text,
    '{"v":3,' || substr(v_template,2),
    v_template || repeat(' ',40001)
  ]) loop
    perform pg_temp.check_true(not public._face_template_valid_v3(v_bad), 'malformed/legacy/oversize template rejected #' || coalesce(length(v_bad),-1));
  end loop;
  perform pg_temp.check_true(public._face_device_hash_v3(t) = public._face_device_hash_v3(upper(t)), 'device hex is byte-normalized');
  perform pg_temp.check_true(public._face_device_hash_v3(repeat('g',64)) is null
    and public._face_device_hash_v3(repeat('a',63)) is null and public._face_device_hash_v3(null) is null,
    'malformed and null device tokens rejected');

  r := public.admin_list_staff();
  select value into a from jsonb_array_elements(r->'rows') where value->>'id' = s::text;
  perform pg_temp.check_true(a->>'face_version' = '2' and a->>'face_registered' = 'false' and a->>'device_activated' = 'false',
    'legacy v2 is not registered or activated');
  select value into a from jsonb_array_elements(r->'rows') where value->>'mobile_number' = 'fixture-legacy';
  perform pg_temp.check_true(a->>'face_version' = '0', 'legacy v1 reports version zero');
  r := public.login_staff_v3('fixture-staff',t);
  perform pg_temp.check_true(r = public.login_staff_v3('unknown-mobile',t) and r->>'reason' = 'activation_required'
    and not (r ? 'staff'), 'unactivated and unknown mobile deny generically without payload');
  perform pg_temp.check_true(public.login_staff_v3('fixture-staff',null)->>'ok' = 'false', 'null token denies login');
  perform pg_temp.check_true(public.save_face_template_v3(s,t,v_template)->>'ok' = 'false', 'unenrolled unactivated device cannot save');
  perform pg_temp.check_true(public.get_today_v3(s,t)->>'ok' = 'false'
    and public.get_history_v3(s,t,current_date,current_date)->>'ok' = 'false', 'unactivated snapshots denied');

  a := public.admin_issue_face_activation(s);
  code := a->>'activation_code';
  perform pg_temp.check_true(a->>'ok' = 'true' and code ~ '^[0-9A-F]{12}$'
    and (a->>'expires_at')::timestamptz between clock_timestamp() + interval '14 minutes' and clock_timestamp() + interval '15 minutes',
    'admin gets 48-bit one-time code with 15-minute expiry');
  perform pg_temp.check_true((select face_template like '%"app"%' from public.staff where id=s)
    and not (select enrollment_allowed from public.staff_face_access where staff_id=s), 'issuing code does not clear face or unlock enrollment');
  for i in 1..5 loop
    r := public.login_staff_v3('fixture-staff',t,'wrong');
    perform pg_temp.check_true(r->>'reason' = 'invalid_activation' and not (r ? 'staff')
      and (select activation_failed_attempts=i from public.staff_face_access where staff_id=s), 'wrong activation attempt persisted ' || i);
  end loop;
  perform pg_temp.check_true(public.login_staff_v3('fixture-staff',t,code)->>'ok' = 'false', 'fifth wrong attempt locks even correct code');
  a := public.admin_issue_face_activation(s);
  code := a->>'activation_code';
  update public.staff_face_access set activation_expires_at=clock_timestamp()-interval '1 second' where staff_id=s;
  perform pg_temp.check_true(public.login_staff_v3('fixture-staff',t,code)->>'ok' = 'false', 'expired activation denied');
  a := public.admin_issue_face_activation(s);
  code := a->>'activation_code';
  r := public.login_staff_v3('fixture-staff',t,lower(code));
  perform pg_temp.check_true(r->>'ok'='true' and r#>>'{staff,face_enrollment_allowed}'='true'
    and r#>'{staff,face_template}'='null'::jsonb and r#>'{staff,face_revision}'='null'::jsonb,
    'activation clears legacy v2 and authorizes one enrollment');
  perform pg_temp.check_true((select activation_code_hash is null and device_token_hash=pg_catalog.sha256(decode(t,'hex'))
    from public.staff_face_access where staff_id=s), 'activation stores only token hash and erases consumed code');
  perform pg_temp.check_true(public.login_staff_v3('fixture-staff',t2,code)->>'ok'='false', 'activation code cannot be reused on another installation');
  perform pg_temp.check_true(public.login_staff_v3('fixture-staff',upper(t))->>'ok'='true', 'installation token supports repeat mobile login');
  perform pg_temp.check_true(public.get_today_v3(s,t)->>'ok'='true'
    and public.get_history_v3(s,t,current_date,current_date)->>'ok'='true', 'activated enrollment screens can load snapshots');
  perform pg_temp.check_true(public.begin_face_check_v3(s,t,'on')->>'ok'='false'
    and public.record_pulse_v3(s,t,21,81)->>'ok'='false', 'challenge and pulse require completed v3 enrollment');
  perform pg_temp.check_true(public.save_face_template_v3(s,t,'{"v":2,"app":"fixture"}')->>'ok'='false'
    and (select enrollment_allowed from public.staff_face_access where staff_id=s), 'invalid template leaves enrollment available');
  r := public.save_face_template_v3(s,t,v_template);
  rev := (r->>'face_revision')::uuid;
  perform pg_temp.check_true(r->>'ok'='true' and rev is not null, 'valid enrollment assigns fresh revision');
  perform pg_temp.check_true(public.save_face_template_v3(s,t,v_template)->>'ok'='false'
    and (select template_revision=rev and not enrollment_allowed from public.staff_face_access where staff_id=s), 'second enrollment cannot overwrite locked template');
  r := public.login_staff_v3('fixture-staff',t);
  perform pg_temp.check_true(r#>>'{staff,face_revision}'=rev::text and r#>>'{staff,face_enrollment_allowed}'='false'
    and r#>>'{staff,face_template}'=v_template
    and (r #- '{staff,face_revision}' #- '{staff,face_enrollment_allowed}')=public.login_by_mobile('fixture-staff'), 'known device gets original login payload plus face metadata');
  r := public.admin_list_staff();
  select value into a from jsonb_array_elements(r->'rows') where value->>'id'=s::text;
  perform pg_temp.check_true(a ?& array['id','full_name','employee_code','mobile_number','role','category','face_registered','kendra_names','face_version','device_activated']
    and a->>'face_version'='3' and a->>'face_registered'='true' and a->>'device_activated'='true', 'admin list preserves fields and marks locked v3 only');

  perform pg_temp.check_true(public.begin_face_check_v3(s,t,'bad')->>'ok'='false'
    and public.begin_face_check_v3(s,t,null)->>'ok'='false', 'unknown and null actions rejected');
  c := public.begin_face_check_v3(s,t,'on');
  old_cid := (c->>'challenge_id')::uuid;
  perform pg_temp.check_true(c->>'ok'='true' and c->>'expires_in'='90' and c->>'turn' in ('left','right')
    and c->>'face_revision'=rev::text, 'challenge returns UUID, random turn, 90 seconds and revision');
  perform pg_temp.check_true((select expires_at between clock_timestamp()+interval '89 seconds' and clock_timestamp()+interval '90 seconds'
    from public.staff_face_challenges where staff_id=s), 'challenge expiry is 90 seconds');
  c := public.begin_face_check_v3(s,t,'on');
  cid := (c->>'challenge_id')::uuid;
  perform pg_temp.check_true(cid<>old_cid and public.record_check_in_v3(s,t,21,81,old_cid)->>'ok'='false'
    and (select count(*)=1 from public.staff_face_challenges where staff_id=s), 'new challenge replaces the previous per-staff nonce');
  perform pg_temp.check_true(public.record_check_out_v3(s,t,21,81,p_challenge_id=>cid)->>'ok'='false', 'on challenge cannot authorize manual off');
  r := public.record_check_in_v3(s,t,'NaN'::double precision,81,cid);
  perform pg_temp.check_true(r->>'ok'='false' and not exists(select 1 from public.staff_face_challenges where staff_id=s), 'NaN punch rejected but valid nonce consumed');
  perform pg_temp.check_true(public.record_check_in_v3(s,t,21,81,cid)->>'ok'='false', 'consumed nonce cannot be replayed');
  c := public.begin_face_check_v3(s,t,'on'); cid := (c->>'challenge_id')::uuid;
  r := public.record_check_in_v3(s,t,-21,-81,cid);
  perform pg_temp.check_true(r->>'reason'='outside_geofence' and not exists(select 1 from public.staff_face_challenges where staff_id=s), 'downstream geofence denial still consumes nonce');
  c := public.begin_face_check_v3(s,t,'on'); cid := (c->>'challenge_id')::uuid;
  update public.staff_kendra set check_in_start='23:59:59.999999' where staff_id=s;
  r := public.record_check_in_v3(s,t,21,81,cid);
  perform pg_temp.check_true(r->>'reason'='outside_window' and not exists(select 1 from public.staff_face_challenges where staff_id=s), 'downstream window denial still consumes nonce');
  update public.staff_kendra set check_in_start='00:00' where staff_id=s;
  c := public.begin_face_check_v3(s,t,'on'); cid := (c->>'challenge_id')::uuid;
  update public.staff_face_challenges set expires_at=clock_timestamp()-interval '1 second' where staff_id=s;
  perform pg_temp.check_true(public.record_check_in_v3(s,t,21,81,cid)->>'ok'='false', 'expired challenge denied');
  c := public.begin_face_check_v3(s,t,'on'); cid := (c->>'challenge_id')::uuid;
  update public.staff_face_challenges set template_revision=gen_random_uuid() where staff_id=s;
  perform pg_temp.check_true(public.record_check_in_v3(s,t,21,81,cid)->>'ok'='false', 'revision mismatch denied');
  c := public.begin_face_check_v3(s,t,'on'); cid := (c->>'challenge_id')::uuid;
  update public.staff_face_challenges set device_token_hash=public._face_device_hash_v3(t2) where staff_id=s;
  perform pg_temp.check_true(public.record_check_in_v3(s,t,21,81,cid)->>'ok'='false', 'challenge token-hash mismatch denied');

  c := public.begin_face_check_v3(s,t,'on'); cid := (c->>'challenge_id')::uuid;
  select pg_get_functiondef('public.record_check_in(uuid,double precision,double precision)'::regprocedure) into original_definition;
  execute 'create or replace function public.record_check_in(p_staff_id uuid,p_lat double precision,p_long double precision) returns jsonb language plpgsql as $f$ begin raise exception ''private failure detail''; end $f$';
  r := public.record_check_in_v3(s,t,21,81,cid);
  perform pg_temp.check_true(r->>'error'='Request denied.' and not exists(select 1 from public.staff_face_challenges where staff_id=s), 'delegate SQL exception is generic and does not roll back consumed check-in nonce');
  execute original_definition;
  c := public.begin_face_check_v3(s,t,'off'); cid := (c->>'challenge_id')::uuid;
  r := public.record_check_out_v3(s,t,21,81,p_challenge_id=>cid);
  perform pg_temp.check_true(r->>'ok'='false' and not exists(select 1 from public.staff_face_challenges where staff_id=s), 'off nonce consumed when delegate rejects missing check-in');
  c := public.begin_face_check_v3(s,t,'on'); cid := (c->>'challenge_id')::uuid;
  perform pg_temp.check_true(public.record_check_in_v3(s,t,21,81,cid)->>'status'='checked_in', 'authorized on challenge delegates check-in');
  perform pg_temp.check_true(public.record_pulse_v3(s,t,21,81)->>'ok'='true', 'enrolled device pulse delegates without face challenge');
  perform pg_temp.check_true(public.record_pulse_v3(s,t,'Infinity',81)->>'ok'='false'
    and public.record_pulse_v3(s,t,21,'-Infinity')->>'ok'='false'
    and public.record_pulse_v3(s,t,91,81)->>'ok'='false'
    and public.record_pulse_v3(s,t,21,181)->>'ok'='false'
    and public.record_pulse_v3(s,t,null,81)->>'ok'='false', 'pulse rejects null, out-of-bounds and infinite coordinates');
  perform pg_temp.check_true(public.record_check_out_v3(s,t,21,81)->>'ok'='false', 'manual off cannot omit challenge');
  perform pg_temp.check_true(public.record_check_out_v3(s,t,21,81,'left_area')->>'ok'='false'
    and public.record_check_out_v3(s,t,21,81,null)->>'ok'='false', 'unknown and null checkout reasons rejected');
  perform pg_temp.check_true(public.record_check_out_v3(s,t,21,81,'day_end')->>'ok'='false', 'day_end cannot be forged before assigned deadline');
  c := public.begin_face_check_v3(s,t,'off'); cid := (c->>'challenge_id')::uuid;
  select pg_get_functiondef('public.record_check_out(uuid,double precision,double precision,text)'::regprocedure) into original_definition;
  execute 'create or replace function public.record_check_out(p_staff_id uuid,p_lat double precision,p_long double precision,p_reason text default ''manual'') returns jsonb language plpgsql as $f$ begin raise exception ''private failure detail''; end $f$';
  r := public.record_check_out_v3(s,t,21,81,p_challenge_id=>cid);
  perform pg_temp.check_true(r->>'error'='Request denied.' and not exists(select 1 from public.staff_face_challenges where staff_id=s), 'delegate SQL exception does not roll back consumed checkout nonce');
  execute original_definition;
  c := public.begin_face_check_v3(s,t,'off'); cid := (c->>'challenge_id')::uuid;
  perform pg_temp.check_true(public.record_check_out_v3(s,t,21,81,p_challenge_id=>cid)->>'status'='checked_out', 'valid off challenge delegates manual checkout');
  perform pg_temp.check_true(public.record_check_out_v3(s,t,21,81,p_challenge_id=>cid)->>'ok'='false', 'manual checkout replay denied');

  update public.attendance set check_out_at=null where staff_id=s;
  update public.staff_kendra set day_end=null where staff_id=s;
  perform pg_temp.check_true(public.record_check_out_v3(s,t,21,81,'day_end')->>'ok'='false', 'missing assigned day_end denied');
  update public.staff_kendra set day_end='00:00:00.000001' where staff_id=s;
  insert into public.staff_kendra(staff_id,kendra_id,check_in_start,check_in_end,day_end)
    values(s,'22222222-0000-0000-0000-000000000002','00:00','00:30','00:00:00.000001');
  perform pg_temp.check_true(public.record_check_out_v3(s,t,21,81,'day_end')->>'ok'='false', 'ambiguous multiple assigned shifts cannot authorize day_end');
  delete from public.staff_kendra where staff_id=s and kendra_id='22222222-0000-0000-0000-000000000002';
  update public.staff_kendra set check_in_start='23:00',day_end='01:00' where staff_id=s;
  perform pg_temp.check_true(public.record_check_out_v3(s,t,21,81,'day_end')->>'ok'='false', 'unsupported overnight shift cannot authorize a premature automatic checkout');
  update public.staff_kendra set check_in_start='00:00',day_end='00:00:00.000001' where staff_id=s;
  perform pg_temp.check_true(public.record_check_out_v3(s,t,22,82,'day_end')->>'status'='checked_out', 'actual current assigned day_end permits no-challenge checkout independently of GPS-nearest kendra');

  c := public.begin_face_check_v3(s,t,'on'); old_cid := (c->>'challenge_id')::uuid;
  old_rev := rev;
  a := public.admin_issue_face_activation(s,true); old_code := a->>'activation_code';
  perform pg_temp.check_true(public.login_staff_v3('fixture-staff',t)->>'ok'='true'
    and (select not enrollment_allowed and template_revision=old_rev from public.staff_face_access where staff_id=s)
    and (select challenge_id=old_cid from public.staff_face_challenges where staff_id=s)
    and (select face_template=v_template from public.staff where id=s), 'issuing reset does not revoke active device, face, revision or challenge');
  a := public.admin_issue_face_activation(s,false); code := a->>'activation_code';
  perform pg_temp.check_true(public.login_staff_v3('fixture-staff',t2,old_code)->>'ok'='false', 'reissued code supersedes old pending code');
  r := public.login_staff_v3('fixture-staff',t2,code);
  perform pg_temp.check_true(r->>'ok'='true' and r#>>'{staff,face_revision}'=old_rev::text
    and r#>>'{staff,face_enrollment_allowed}'='false' and r#>>'{staff,face_template}'=v_template
    and not exists(select 1 from public.staff_face_challenges where staff_id=s), 'successful phone rotation retains valid v3 but invalidates all outstanding challenges');
  perform pg_temp.check_true(public.login_staff_v3('fixture-staff',t)->>'ok'='false'
    and public.get_today_v3(s,t)->>'ok'='false' and public.get_history_v3(s,t,current_date,current_date)->>'ok'='false'
    and public.begin_face_check_v3(s,t,'on')->>'ok'='false' and public.record_pulse_v3(s,t,21,81)->>'ok'='false'
    and public.record_check_in_v3(s,t,21,81,old_cid)->>'ok'='false', 'old device revoked across all device operations');
  a := public.admin_issue_face_activation(s,true); code := a->>'activation_code';
  r := public.login_staff_v3('fixture-staff',t2,code);
  perform pg_temp.check_true(r->>'ok'='true' and r#>>'{staff,face_enrollment_allowed}'='true'
    and r#>'{staff,face_revision}'='null'::jsonb and r#>'{staff,face_template}'='null'::jsonb
    and (select face_template is null from public.staff where id=s), 'explicit correct code atomically resets enrollment even on the same phone');
  r := public.save_face_template_v3(s,t2,v_template); rev := (r->>'face_revision')::uuid;
  perform pg_temp.check_true(r->>'ok'='true' and rev<>old_rev, 'supervised re-enrollment gets a different revision');

  a := public.admin_issue_face_activation(n); code := a->>'activation_code';
  perform pg_temp.check_true(public.login_staff_v3('fixture-nodal',t3,code)->>'ok'='true'
    and public.save_face_template_v3(n,t3,v_template)->>'ok'='true', 'nodal activation and enrollment supported');
  c := public.begin_face_check_v3(n,t3,'on'); cid := (c->>'challenge_id')::uuid;
  perform pg_temp.check_true(public.record_check_in_v3(s,t2,21,81,cid)->>'ok'='false', 'other staff challenge cannot be used');
  perform pg_temp.check_true(public.record_check_in_v3(n,t3,21,81,cid)->>'status'='visit_recorded', 'nodal check-in preserves original visit flow');
  perform pg_temp.check_true(public.record_pulse_v3(n,t3,21,81)->>'visit_open'='true', 'nodal pulse preserves visit tracking');
  perform pg_temp.check_true(public.record_check_out_v3(n,t3,21,81,'day_end')->>'ok'='false', 'nodal staff cannot claim shift day_end exception');
  perform pg_temp.check_true((select count(*)>0 from public.staff_face_activation_audit)
    and (select array_agg(column_name::text order by ordinal_position)=array['admin_id','action','created_at']
      from information_schema.columns where table_schema='public' and table_name='staff_face_activation_audit'), 'audit stores only admin/action/time metadata');
end;
$$;

-- Verify real client-role invocation, not only owner invocation and ACL metadata.
select set_config('request.jwt.claim.sub','',false);
set role anon;
select pg_temp.check_true(public.login_staff_v3('fixture-staff',repeat('cd',32))->>'ok'='true', 'anon activated login succeeds through definer');
select pg_temp.check_true(public.get_today_v3('11111111-0000-0000-0000-000000000001',repeat('cd',32))->>'ok'='true', 'anon snapshot delegates despite legacy execution revocation');
do $$
begin
  begin
    perform public.login_by_mobile('fixture-staff');
    raise exception 'legacy login unexpectedly callable';
  exception when insufficient_privilege then
    perform pg_temp.check_true(true,'anon cannot directly invoke legacy login');
  end;
  begin
    perform public._face_template_valid_v3('{}');
    raise exception 'internal validator unexpectedly callable';
  exception when insufficient_privilege then
    perform pg_temp.check_true(true,'anon cannot directly invoke helpers');
  end;
  begin
    perform 1 from public.staff_face_access;
    raise exception 'access table unexpectedly readable';
  exception when insufficient_privilege then
    perform pg_temp.check_true(true,'anon cannot directly read access hashes');
  end;
end;
$$;
reset role;
set role authenticated;
select pg_temp.check_true(public.admin_issue_face_activation('11111111-0000-0000-0000-000000000001')->>'ok'='false', 'authenticated non-admin cannot issue activation');
select pg_temp.check_true(public.get_today_v3('11111111-0000-0000-0000-000000000001',repeat('cd',32))->>'ok'='true', 'authenticated activated device can read snapshot');
select set_config('request.jwt.claim.sub','aaaaaaaa-0000-0000-0000-000000000001',false);
select pg_temp.check_true(public.admin_list_staff()->>'ok'='true', 'authenticated admin list still works');
reset role;
select count(*) as passed_assertions from test_results;
