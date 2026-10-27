-- =====================================================================
-- Dhan Kharidi Samiksha — kendra editing (migration 3)
-- Run AFTER supabase/admin_dashboard.sql. Single re-runnable transaction.
-- Paste the WHOLE file into the Supabase SQL editor and run it.
--
-- Adds admin_update_kendra() so an admin can correct a kendra's
-- lat/long, radius and time windows from the dashboard (Kendras -> Edit).
-- =====================================================================

begin;

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

grant execute on function public.admin_update_kendra(
  uuid, text, double precision, double precision, integer, time, time, time, integer
) to authenticated;

commit;

-- ---------------------------------------------------------------------
-- VERIFICATION: should return one row, admin_update_kendra
-- ---------------------------------------------------------------------
select routine_name from information_schema.routines
 where routine_schema = 'public'
   and routine_name = 'admin_update_kendra';
