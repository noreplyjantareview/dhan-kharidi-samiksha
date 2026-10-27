# Dhan Kharidi Samiksha

Field-staff duty-attendance system for Chhattisgarh paddy-procurement monitoring. Verifies that
staff actually report to, and stay at, their assigned duty location (**kendra**).

Two parts, one Supabase backend:

1. **Android app** (Kotlin + Compose) — for **field staff**. Mobile-number login, auto geofenced
   check-in/out, and periodic presence re-verification. **Staff-only** (no admin here).
2. **Web admin dashboard** (no-build static site) — for **district administration**. Email/password
   login. View all attendance logs, manage kendras & staff, assign duty locations, and **bulk
   enroll from Excel**.

Stack: Android native · Supabase (Postgres + RPC + Auth) · plain HTML/JS + Tailwind + SheetJS for
the dashboard · GitHub Actions to build the APK and deploy the dashboard.

---

## How attendance works

```
Staff opens app → types registered mobile → login_by_mobile() returns the staff
                    record + their allowed kendra list (fixed = 1, multi = several)
        │
        ▼
App streams GPS (foreground) → AttendanceRules.decide()
        ├─ not checked in + inside an allowed kendra + within window → record_check_in()
        ├─ checked in + every verify_interval_min                     → record_pulse() (present/absent)
        └─ checked in + at/after day_end                              → record_check_out()
        ▼
Server re-validates the geofence distance + IST window before writing anything
```

Presence pulses are the anti-absenteeism trail: arriving-then-leaving, or never staying, shows up
as absent pulses. Geofence math and windows are enforced server-side.

---

## 1. Set up Supabase

Run **all** migrations in the Supabase **SQL Editor** (New query → paste → Run), in order:

1. [`supabase/schema.sql`](supabase/schema.sql) — tables + staff/attendance RPCs.
2. [`supabase/admin_dashboard.sql`](supabase/admin_dashboard.sql) — admin auth allow-list +
   secured dashboard RPCs (also drops the old insecure admin functions).
3. [`supabase/kendra_edit.sql`](supabase/kendra_edit.sql) — `admin_update_kendra()` so kendras
   (lat/long, radius, time windows) can be edited from the dashboard.

All are re-runnable. Verification `SELECT`s at the bottom of each confirm success.

### Create your first kendra

The dashboard can create kendras, but you need at least one to assign staff to. Either create it
from the dashboard after logging in (below), or seed one via the commented block at the bottom of
`schema.sql` (edit name + real lat/long from Google Maps at the kendra).

### Create your first admin (dashboard login)

Admins log in with **email + password** via Supabase Auth, then must be allow-listed:

1. Supabase → **Authentication → Users → Add user** → enter an email + password, tick
   **Auto Confirm User** → create.
2. Copy that user's **User UID**.
3. In the SQL Editor run (replace the UUID and name):
   ```sql
   insert into public.admins (auth_id, full_name)
   values ('00000000-0000-0000-0000-000000000000', 'District Admin')
   on conflict (auth_id) do nothing;
   ```
4. Log into the dashboard with that email + password.

> You create the admin password yourself in the Supabase console — it never passes through the repo
> or this tooling.

---

## 2. Android app (field staff)

### Configure
Copy `local.properties.example` → `local.properties` and fill `SUPABASE_URL` + `SUPABASE_ANON_KEY`
(from Supabase → Project Settings → API). `local.properties` is git-ignored.

### Build without Android Studio (low-spec friendly)
You don't need the IDE. Two options:

- **Cloud (recommended):** push to GitHub — the **Build APK** workflow compiles it and attaches a
  downloadable `app-debug-apk` artifact. See section 4.
- **Local CLI:** install JDK 17 + Android command-line SDK + Gradle, then `gradle assembleDebug`.
  APK at `app/build/outputs/apk/debug/app-debug.apk`. Install with
  `adb install -r <apk>` or sideload the file. Needs a **physical phone with GPS**.

Staff log in with the mobile number an admin enrolled. Admins who log into the phone app see a
notice pointing them to the web dashboard.

---

## 3. Web admin dashboard

A no-build static site in [`dashboard/`](dashboard). Features:

- **Overview** — present / absent / late / checked-out today, total staff & kendras.
- **Attendance logs** — filter by date range, kendra, and name/mobile; shows check-in/out, status,
  and per-day present/absent pulse counts.
- **Staff** — enroll one at a time, and **Assign** allowed kendra(s) (fixed = one, multi = several).
- **Kendras** — create with lat/long, radius, arrival window, day-end, verify interval; **Edit**
  any kendra later (e.g. correct its lat/long). Staff phones pick up the new location the next
  time they open the app.
- **Bulk upload** — upload an `.xlsx`, preview, and enroll many staff at once with per-row results.
  A **Download template** button generates the sheet.

### Excel template columns
| Column | Values |
|---|---|
| Full name | text |
| Mobile number | 10-digit |
| Role | `staff` or `admin` |
| Duty type | `fixed` or `multi` |
| Kendra name(s) | existing kendra name; comma-separated for `multi` |

Kendras are matched **by name** and must already exist. Rows with an unknown kendra or a duplicate
mobile are reported as failures; the rest still import.

### Run locally
Fill your URL + anon key into `dashboard/config.js` (replacing the `__SUPABASE_URL__` /
`__SUPABASE_ANON_KEY__` placeholders), then open `dashboard/index.html` in a browser (a simple
static server works best, e.g. `python -m http.server` in the `dashboard/` folder).

---

## 4. Deploy (GitHub Actions)

Push this folder to a GitHub repo. Two workflows run automatically:

- **[.github/workflows/build-apk.yml](.github/workflows/build-apk.yml)** — builds the Android
  `app-debug.apk`; download it from the run's **Artifacts**.
- **[.github/workflows/deploy-dashboard.yml](.github/workflows/deploy-dashboard.yml)** — deploys
  `dashboard/` to **GitHub Pages**, injecting your Supabase URL + anon key from secrets at deploy
  time (so keys never sit in the repo).

### One-time repo setup
1. **Settings → Secrets and variables → Actions → New repository secret**, add:
   - `SUPABASE_URL`
   - `SUPABASE_ANON_KEY`
   (Both workflows reuse these. The anon key is a *publishable* key — safe in the browser because
   the admin RPCs require an authenticated admin session.)
2. **Settings → Pages → Build and deployment → Source = "GitHub Actions"**.
3. Push. The dashboard URL appears on the deploy run (also `https://<username>.github.io/<repo>/`).

---

## Project layout

```
supabase/schema.sql              # base: tables + staff/attendance RPCs
supabase/admin_dashboard.sql     # admin auth + secured dashboard RPCs + bulk enroll
supabase/kendra_edit.sql         # admin_update_kendra() — edit lat/long & windows
dashboard/                       # no-build web admin (index.html, app.js, config.js, styles.css)
app/src/main/java/com/vsk/attendance/
  MainActivity.kt                # entry, permission gate, staff-only routing
  data/{Models,SupabaseApi,SessionStore}.kt
  util/GeoUtils.kt               # Haversine + IST clock
  location/{LocationHelper,AttendanceRules}.kt
  ui/{MainViewModel,LoginScreen,AttendanceScreen}.kt + theme/
.github/workflows/               # build-apk.yml, deploy-dashboard.yml
```

---

## Per-kendra settings
| Setting | Default | Meaning |
|---|---|---|
| `radius_m` | 100 | Geofence radius (metres) |
| `check_in_start` / `check_in_end` | 08:00 / 10:00 | IST arrival window |
| `day_end` | 18:00 | IST auto check-out deadline |
| `verify_interval_min` | 30 | Presence re-verification interval |

Staff `duty_type`: **fixed** = exactly one kendra (assigning replaces it); **multi** = several
allowed kendras.

---

## Monitoring absenteeism
The `presence_pulses` table is the evidence trail:
```sql
select to_char(at at time zone 'Asia/Kolkata','HH24:MI') as time_ist, inside, distance_m
from presence_pulses
where staff_id = '<staff-uuid>' and day = current_date
order by at;
```
Early present pulse + many absent pulses = "came, then left." No check-in + no pulses = "never went."

---

## Security model
- **Field staff (Android):** mobile-number-only login by design. The geofence + server-side
  validation stop fake-location attendance, and pulses prove continued presence — but the account
  itself is not strongly authenticated. Add OTP (Supabase Phone Auth or an OTP table + per-user
  JWT) for production hardening.
- **Admins (web):** email/password via Supabase Auth, and every admin RPC calls `assert_admin()`
  which checks `auth.uid()` against the `admins` allow-list. The old spoofable `p_admin_id`
  functions were dropped. RLS locks all tables; access is only through SECURITY DEFINER functions.
- GPS can be spoofed on a rooted device; for high assurance add Play Integrity attestation.

---

## Notes & limits
- Attendance tracking is **foreground only** (app open). Working with the app closed needs Android
  background-location permission + a foreground service + battery-optimization handling.
- The code has not been compiled in this environment; the first APK build happens in CI or on your
  machine. Fix any version nits that surface there.
