# Dhan Kharidi Samiksha — field-staff duty attendance

Android app to verify that field staff actually report to, and stay at, their assigned duty
location (procurement **kendra**). Built for the problem where staff were posted to a location
but did not go to duty.

- **Users do not pick a location.** The app reads device GPS on its own.
- **Login = registered mobile number only** (no password, no OTP).
- **Admin** creates kendras, enrolls staff, and assigns each person's allowed duty location(s).
- **Two duty types:**
  - **Fixed** — the person is tied to exactly one kendra.
  - **Multi** — the person may mark attendance from several allowed kendras (e.g. a mobile officer).
- **Auto check-in / check-out:** check-in fires when the person is inside an allowed kendra during
  the arrival window; check-out fires automatically at the kendra's day-end time.
- **Periodic re-verification (the anti-absenteeism core):** during duty hours the app sends a
  presence "pulse" every N minutes. Each pulse is logged as **present** (inside an allowed kendra)
  or **absent** (outside). Arriving-then-leaving, or never staying, shows up as absent pulses.
- **Foreground only:** tracking runs while the app is open — battery-friendly, no background
  location permission.

Stack: **Android native (Kotlin + Jetpack Compose)** · **Supabase (Postgres + RPC)** ·
**Google Play Services Fused Location**.

---

## How it works

```
Staff opens app → types registered mobile → login_by_mobile() returns the staff
                    record + their allowed kendra list (fixed = 1, multi = several)
        │
        ▼
App streams GPS (foreground) → AttendanceRules.decide()
        │
        ├─ not checked in + inside an allowed kendra + within window → record_check_in()
        ├─ checked in + every verify_interval_min                     → record_pulse()  (present/absent)
        └─ checked in + at/after day_end                              → record_check_out()
        │
        ▼
Server re-validates the geofence distance + IST time window before writing anything
```

The geofence math, time windows and pulse logging are enforced **server-side** in Postgres, so a
tampered client cannot fake presence at a kendra it is not physically at.

---

## 1. Set up Supabase

1. Create a project at https://supabase.com (or use an existing one).
2. Open **SQL Editor → New query**.
3. Paste the **entire** contents of [`supabase/schema.sql`](supabase/schema.sql) and **Run**.
   One re-runnable transaction: creates `kendras`, `staff`, `staff_kendra`, `attendance`,
   `presence_pulses`, all RPC functions, locks tables with RLS, and grants execute to anon.
4. The verification `SELECT`s at the bottom should list all 5 tables and all 13 functions.

### Create your first admin + kendra

The Admin screen can create kendras and enroll staff, but you need **one admin row** to log in the
first time. In the SQL Editor, edit and run the seed block at the bottom of `schema.sql`
(it is commented out). Set a real kendra `name`, `latitude`, `longitude` (exact coordinates from
Google Maps at the kendra), `radius_m`, and the admin's real `mobile_number`. Then run just that
block.

Log into the app with that mobile number → Admin screen → add kendras, enroll staff (choose
fixed/multi), and assign each person their allowed kendra(s).

---

## 2. Configure the Android app

Your Supabase URL and anon key are **secrets you fill in yourself** — never committed.

1. Copy `local.properties.example` to `local.properties` in the project root.
2. From Supabase → **Project Settings → API**, copy the **Project URL** and **anon public** key into
   `SUPABASE_URL` and `SUPABASE_ANON_KEY`.
3. Leave `sdk.dir` as-is (Android Studio fills it in).

`local.properties` is git-ignored; Gradle reads it into `BuildConfig`. If the values are missing,
the login screen shows a clear "Supabase not configured" banner instead of crashing.

---

## 3. Build & run

1. Open the project folder in **Android Studio** (Ladybug or newer).
2. Let it sync Gradle (downloads the wrapper + dependencies).
3. Run on a **physical device** (geofencing needs a real GPS fix).
4. Log in with an enrolled mobile number.

Minimum SDK 24 (Android 7.0), target SDK 35.

---

## Project layout

```
supabase/schema.sql                     # backend migration (tables + RPC + RLS)
app/src/main/java/com/vsk/attendance/
  MainActivity.kt                       # entry, permission gate, screen routing
  data/Models.kt                        # serializable DTOs (Kendra, Staff, pulses…)
  data/SupabaseApi.kt                   # Ktor client → Supabase RPC
  data/SessionStore.kt                  # SharedPreferences session (staff + allowed kendras)
  util/GeoUtils.kt                      # Haversine + IST clock helpers
  location/LocationHelper.kt            # Fused Location (one-shot + stream)
  location/AttendanceRules.kt           # multi-kendra + pulse decision engine
  ui/MainViewModel.kt                   # state, duty loop, admin actions
  ui/LoginScreen.kt / AttendanceScreen.kt / AdminScreen.kt
  ui/theme/Theme.kt
```

---

## Per-kendra settings (configurable in the Admin screen)

| Setting               | Default | Meaning                                              |
|-----------------------|---------|------------------------------------------------------|
| `radius_m`            | 100     | Geofence radius around the kendra lat/long (metres)  |
| `check_in_start`      | 08:00   | IST arrival window opens                             |
| `check_in_end`        | 10:00   | IST arrival window closes                            |
| `day_end`             | 18:00   | IST auto check-out deadline                          |
| `verify_interval_min` | 30      | How often presence is re-verified during duty        |

One attendance row per staff per IST day. Check-in more than 30 min after `check_in_start` is
flagged `late`. Every re-verification is stored in `presence_pulses` (present/absent + distance),
so a full day's presence is auditable, not just the arrival time.

### Staff duty types

| duty_type | Meaning                                                        |
|-----------|----------------------------------------------------------------|
| `fixed`   | Tied to exactly one kendra (assigning a new one replaces it).   |
| `multi`   | May mark attendance from any of several assigned kendras.       |

---

## Reporting / monitoring absenteeism

The `presence_pulses` table is the evidence trail. Example query for a staff member's day:

```sql
select to_char(at at time zone 'Asia/Kolkata','HH24:MI') as time_ist,
       inside, distance_m
from presence_pulses
where staff_id = '<staff-uuid>'
  and day = current_date
order by at;
```

A day with an early present pulse followed by many absent pulses = "came, then left."
No pulses at all + no check-in = "never went to duty." You can build an admin dashboard on this
later (a monitoring web view, or an officer-review screen) — say the word.

---

## Security note (read before real deployment)

**Login is mobile-number-only, by design** (that's the requested flow). This is **weak**: anyone
who knows a staff member's registered number can log in as them. The geofence + server-side
validation stops *fake-location* attendance, and pulses prove continued presence — but they do not
protect the account itself. Admin functions trust a passed-in admin id, which is likewise spoofable.

For anything beyond a pilot, add **OTP verification** on the mobile number:
- Supabase Phone Auth (Twilio/MessageBird), or
- an OTP table + `verify_otp()` RPC issuing a real per-user JWT so RLS can scope each user to their
  own rows.

I can add the OTP layer around the existing `login_by_mobile` flow whenever you want.

---

## Notes & limits

- Foreground only: tracking runs while the app is open. Working with the app closed needs Android
  background-location permission + a foreground service + battery-optimization handling.
- GPS can be spoofed with mock-location apps; server-side geofencing raises the bar but is not
  tamper-proof on a rooted device. For high assurance add Play Integrity attestation.
