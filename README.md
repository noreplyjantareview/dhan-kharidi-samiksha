# Dhan Kharidi Samiksha

Field-staff duty-attendance system for Chhattisgarh paddy-procurement monitoring. Verifies that
staff actually report to, and stay at, their assigned duty location (**kendra**).

Two parts, one Supabase backend:

1. **Android app** (Kotlin + Compose) — for **field staff**. Mobile-number login, one-time face
   registration, face-verified **On Duty / Off Duty** buttons, geofence validation and periodic
   presence re-verification. **Staff-only** (no admin here).
2. **Web admin dashboard** (no-build static site) — for **district administration**. Email/password
   login. View all attendance logs, manage kendras & staff, assign duty locations, and **bulk
   enroll from Excel**.

Stack: Android native · Supabase (Postgres + RPC + Auth) · plain HTML/JS + Tailwind + SheetJS for
the dashboard · GitHub Actions to build the APK and deploy the dashboard.

---

## How attendance works

```
Administrator verifies employee identity → issues one-time phone activation code
Staff types registered mobile (+ activation code on a new phone) → login_staff_v3()
        │  supervised enrollment: the camera runs the check itself, no photos
        ▼
App streams GPS (foreground) while open
        ├─ On Duty → server challenge → camera runs the blink + face check → record_check_in_v3()
        ├─ nodal On Duty at an allowed kendra → same face check → visit stamped
        ├─ every verify_interval_min while on duty / visit open → record_pulse_v3()
        ├─ Off Duty → server challenge → same automatic check → record_check_out_v3()
        └─ at/after assigned day_end → device-authorized automatic check-out
        ▼
Server re-validates the geofence distance + IST window before writing anything.
Check-out stores work_minutes (total duty duration). On Duty stays locked for the day after that.
```

Check-in window: the admin sets one **check-in time per staff→kendra assignment** (Staff →
Assign); the window closes automatically **30 minutes** later (16:00 → last check-in 16:30).
Arrivals more than **15 minutes** after the check-in time are flagged `late`. Nodal officers have
no window — each face-checked arrival inside an allowed kendra is stamped as a **visit**.

Presence pulses are the anti-absenteeism trail: arriving-then-leaving, or never staying, shows up
as absent pulses. Geofence math and windows are enforced server-side.

### Strict monitoring (migration 6)

- **Day-quality flags** — `admin_attendance_log()` returns `absent_streak_min` (longest continuous
  run of absent pulses) and `silence_min` (longest gap between check-in, pulses and check-out —
  i.e. the app was closed or GPS was off). The dashboard log shows them as badges:
  `OUT xm` (absent streak ≥ 15 min), `DARK xm` (silence > 60 min), `LEFT AREA` (auto-closed, below).
- **Left-area auto check-out** — when `record_pulse()` sees a trailing run of absent pulses spanning
  **30 minutes or more**, it closes the duty server-side with `check_out_reason = 'left_area'` and
  `work_minutes` counted only up to the last verified **present** pulse. The day then stays locked
  like any checked-out day. The app shows the reason on the duty-closed card.
- **Mock-location rejection** — the app refuses to use GPS fixes flagged as mock
  (`Location.isMock` / `isFromMockProvider`), so fake-GPS apps cannot pulse, check in or check out;
  the screen says why.

### Shifts, employee codes & nodal officers (migration 7)

- **Shift timing belongs to the person, not the place.** A kendra now stores only its location
  (lat/long, radius) and `verify_interval_min`. The check-in time, +30 min closing and day-end live
  on each **staff→kendra assignment**, so one kendra can host many staff on different shifts. Set
  or change a shift from **Staff → Assign**: pick the kendra, set check-in time + day ends.
- **Employee code** — every staff record carries a unique code (existing rows were backfilled
  `EMP-0001`, `EMP-0002`, …). It is required at enrollment, shown in the app and in every report.
- **Category** — `staff` = reports to exactly one kendra on their own shift (assigning a new kendra
  replaces the old one); `nodal` = may visit several kendras with no fixed time.
- **Nodal visits** — a nodal officer taps On Duty with a face check inside an allowed kendra; that
  stamps a visit (arrival timestamp + location). Presence pulses keep it open (`last_pulse_at`);
  there is **no Off Duty** for nodal. The dashboard's **Nodal visits** report lists officer, code,
  kendra, arrival time, last seen and minutes on site, filterable by date/kendra/search.
- The migration copies each kendra's old timings into its existing staff assignments, so current
  shifts survive the upgrade unchanged.

### Face verification (migrations 9 and 10)

- **One model in the app: Google MediaPipe Face Mesh.** FaceLandmarker reads the 478-point mesh on
  every guidance frame. Identity is a **shape print** of that mesh: 39 points sampled on a fixed
  stride, re-centred on the midpoint between the eyes, rotated so the eye line is level and divided
  by the inter-ocular distance, then unit-normalized into 78 numbers (`mesh-print-v1`). Because index
  *i* of the mesh is the same anatomical point on every face, the print needs no hand-picked anatomy
  table and no trained recognition model.
- **OpenCV is gone.** YuNet, SFace and MiniFASNetV2, the `org.opencv:opencv:4.12.0` dependency and
  43 MB of ONNX assets were removed; `app/src/main/assets/face/` is now 3.77 MB and the only native
  library left is MediaPipe's. That also removes the `CvException … Android Bitmap` assert that was
  killing registration on the field phone.
- **What that trade buys, and what it costs:** MediaPipe tasks-vision 1.1.1 publishes no face
  recognition or embedding task, so nothing in this build compares faces the way SFace did. The print
  compares the *shape* of a face, so same-person and different-person scores sit close together (see
  **Accuracy and limits** below) and the operating point is a guess until a phone measures it.
  Presentation defence is now the blink alone: a printed photo cannot blink, but a rehearsed video
  replay of the employee blinking can. Supervised activation is what still binds a registration to a
  physical phone handed over in person.
- **Registration stores five samples, not one:** two centred frames build the identity anchor, one
  blink proves a live person is driving the camera, three post-blink frames finish the five-sample
  template. Every stored sample has to agree with the anchor, so a second person appearing
  mid-capture restarts the check. The randomized head turn is gone — field staff could not read and
  follow it while holding a phone.
- **No button, no dead end:** the check starts as soon as the camera binds and needs no press, so a
  registration is one continuous action. A 120-second window that runs out simply starts again; a
  failed save or a failed duty submission re-runs the check on its own. Only four consecutive broken
  frames, or a model/camera that cannot start, stop the card.
- **A field failure names itself:** release builds still show the plain one-line instruction, but
  debug builds append the root cause (class + message) *and* the measured mesh score against the
  threshold, e.g. `No match [mesh 0.99871 needs 0.99900]`. One screenshot from the field is now
  enough to calibrate the operating point instead of guessing at it.
- **Light and blur without OpenCV:** `FaceFrameQuality` grid-samples the face region — the mesh's own
  bounding box plus an 8 % margin, so the wall behind the employee is not what gets metered — and
  measures brightness, contrast and mean |Laplacian| from the pixels. Too dark, too bright, flat
  light and out-of-focus each get their own line.
- **Every frame is normalized first:** MediaPipe's image builder only accepts an `ARGB_8888` bitmap,
  and a camera does not always deliver the requested pixel format, so each frame is rotated into an
  ARGB_8888 bitmap before inference. A frame that arrives already recycled is reported as such
  instead of surfacing the same native assert.
- **MediaPipe owns whatever bitmap it is given:** closing the image it built runs `recycle()` on that
  bitmap, which is why `FaceGuide.detect` hands inference a private copy. The caller's frame has to
  survive the mesh pass because the light and sharpness meter reads its pixels right afterwards — on
  the field phone this showed up as `Can't call getPixels() on the recycled bitmap` the moment a face
  appeared, because the very first anchor sample needed those pixels.
- **No face photos are saved or uploaded.** Frames and landmarks exist only in memory. Numeric
  templates are still sensitive biometric data: they are stored in the protected staff record and
  encrypted locally with AndroidKeyStore AES-GCM. Device credentials are encrypted too; app backups
  are disabled.
- **Supervised activation:** Dashboard → Staff → Activate phone. Personally verify the employee,
  issue a code and share it privately. Codes expire after 15 minutes, work once, and lock after five
  wrong attempts. Normal subsequent login needs only the mobile number on the activated phone.
  Issuing a code leaves the current phone working; redeeming it replaces that phone. Choose face
  reset only when a new supervised registration is needed — which is exactly what every **Old print ·
  re-enrol** row needs. Uninstalling/reinstalling requires activation.
- **Protected attendance RPCs:** a device token plus a 90-second, action-bound, single-use challenge
  is required for manual On/Off Duty. Server time controls automatic day-end checkout. Legacy
  unauthenticated attendance/template RPCs are revoked. Deploy migration 10, dashboard and APK
  together; older APKs intentionally stop working after migration 9.
- **Template contract:** the stored envelope is unchanged (`v: 3`, exactly three JSON keys, five
  unit-norm samples, ≤ 40 000 characters). Migration 10 binds the sample length to the model tag —
  `mesh-print-v1` × 78 or `sface-2021dec` × 128 — and reports the mesh print as `face_version` 4, so
  both shapes validate but the dashboard can tell which build each person can use. The blink change
  needed no SQL at all: `staff_face_challenges.turn` is still returned by `begin_face_check_v3()` and
  was never part of the proof, so it is dead data.

**Capture timing (retuned for the blink flow):** a registration or duty check runs on a rolling
policy rather than a strict per-frame deadline, because mid-range phones need up to ~1.5 s per frame.
In browser validation on a real webcam the whole sequence took **9.1 s** at 30 fps with three guidance
lines; the head-turn flow it replaced routinely exceeded the 90-second challenge.

- Guidance frames are read about eight times a second; two evidence frames are only accepted 350 ms
  apart, and one window is 120 seconds. A window that expires is not an error: a new one starts on the
  same screen, so the employee never has to press anything to try again.
- A blink is a natural close-and-reopen within 1.2 s, measured against the person's own open-eye
  baseline. Holding the eyes shut is not accepted, and one visible closed frame is enough on a slow
  phone.
- Post-blink sampling must finish inside 4 s, and the framing must hold: a face that drifts out of
  frame or fails the mesh quality gate discards only the samples taken after the blink, never the
  anchor.
- Quality rejections (move closer, hold still, lighting) no longer restart a check in progress.
  Previously any 1.8 s of rejections wiped the collected samples, so registration could not finish.
- A missed detection restarts the check only after four consecutive empty frames; eyes held shut for
  more than four frames abandon the post-blink window. Someone else switching in front of the camera,
  a second face, or a print that does not match the anchor resets immediately, as does four seconds
  with no frame at all.
- A duty check still has to beat the 90-second challenge. If it expires, the screen asks for a new
  face capture; the timeout was not raised to cover this, because the nonce TTL is the security bound.

**Accuracy and liveness limits:** this is not Aadhaar FaceRD, certified PAD or a measured equivalent.
The mesh print compares at `MATCH_THRESHOLD = 0.999` cosine, and the second-best sample in the five
must clear it too; the light/blur gate needs at least `MIN_EDGES = 5` gradient edges in the face
region. Both numbers come from synthetic geometry only — same-face prints of one person sit near
0.9999 and unrelated faces near 0.97–0.995 — so they are provisional operating points, not accuracy
percentages. The debug line names the real value on every failure (`No match [mesh 0.99871 needs
0.99900]`); read it on the actual phones before changing anything, and do not lower a threshold just
to make retries pass.
The blink is a liveness *aid*, not a presentation-attack classifier: a high-quality video of the
employee blinking can satisfy it, and nothing in this build screens for a replay.
Employees who cannot blink on command (paralysis, severe ptosis, one eye) currently cannot complete
the check: they need a supervised exemption, not a lower threshold.
A modified/rooted app can bypass client-side face checks despite device authorization and nonces.
Higher assurance requires a trusted attestation/verification architecture, not just another model.

Before production, use consenting employees to measure genuine acceptance/retry rates across indoor,
outdoor and low-light sessions, glasses, age ranges and skin tones; record timing and pass/fail only,
not images. Test different-person attempts, printed photos, phone-screen photos and replay videos,
challenge expiry/replay, phone replacement and account reset. Report false acceptance/rejection
separately, with trial counts. A small successful demo is not evidence of Aadhaar-like accuracy.

---

## 1. Set up Supabase

Run **all** migrations in the Supabase **SQL Editor** (New query → paste → Run), in order:

1. [`supabase/schema.sql`](supabase/schema.sql) — tables + staff/attendance RPCs.
2. [`supabase/admin_dashboard.sql`](supabase/admin_dashboard.sql) — admin auth allow-list +
   secured dashboard RPCs (also drops the old insecure admin functions).
3. [`supabase/kendra_edit.sql`](supabase/kendra_edit.sql) — `admin_update_kendra()` so kendras
   (lat/long, radius, time windows) can be edited from the dashboard.
4. [`supabase/duty_flow.sql`](supabase/duty_flow.sql) — On/Off Duty flow: `work_minutes` stored at
   check-out, face-template column + `save_face_template()`, 30-min check-in buffer, 15-min late
   rule, and the new fields in `get_today` / `admin_attendance_log` / `login_by_mobile`.
5. [`supabase/face_template_v2.sql`](supabase/face_template_v2.sql) — lets already-registered staff
   upgrade their face template once (v1 geometry-only → v2 with appearance signature); v2
   templates stay overwrite-protected.
6. [`supabase/strict_monitor.sql`](supabase/strict_monitor.sql) — strict monitoring: day-quality
   flags in the attendance log, left-area auto check-out, and the check-out reason column.
7. [`supabase/shifts_nodal.sql`](supabase/shifts_nodal.sql) — shift timing moves from kendra to each
   staff→kendra assignment; unique employee codes; `staff`/`nodal` categories; `visits` table +
   nodal visit recording; **Nodal visits** report RPC. Run this **before** installing the matching
   APK — the app and dashboard call the new RPC signatures.
8. [`supabase/bulk_kendras.sql`](supabase/bulk_kendras.sql) — `admin_bulk_kendras()` so the dashboard
   can create many kendras from one Excel/CSV upload, with per-row validation (required name,
   numeric lat/long in range, radius + verify-interval bounds, duplicate-name rejection).
9. [`supabase/face_auth_v3.sql`](supabase/face_auth_v3.sql) — supervised phone activation, locked
   v3 enrollment, device-authorized attendance and single-use face-check challenges. Run the entire
   transaction once; read-only checks follow it. Activate every existing employee and re-enroll
   their face using the matching new APK.
10. [`supabase/face_mesh_v4.sql`](supabase/face_mesh_v4.sql) — accepts the MediaPipe Face Mesh shape
    print (`mesh-print-v1`, five 78-value samples) next to the existing `sface-2021dec` print, and
    reports which is which (`face_version` 4 = mesh, 3 = SFace). Run it after migration 9, then
    deploy the matching dashboard and APK. **Do not rerun older migrations afterward:** they can
    restore legacy RPC grants; migration 10 must remain last.

All are re-runnable. Verification `SELECT`s at the bottom of each confirm success.

**Re-enrolling after migration 10:** nobody is blocked by the engine swap. A phone still running the
older APK keeps its SFace print and keeps marking attendance. A phone on the new APK cannot read that
print, so its Face column says **Old print · re-enrol**: issue an activation code with
**Activate phone and reset face** and the employee registers once more, supervised. A plain activation
keeps the old print, so the reset option is the only thing that moves someone off it — the last
verification query in migration 10 counts how many people are still on it.

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

Staff log in with the mobile number an admin enrolled, plus a one-time activation code on a new
installation. Admins who log into the phone app see a notice pointing them to the web dashboard.
Android screenshots/recording and recent-app thumbnails are blocked to keep camera frames out of
system captures. Inference needs no model download. Bundled `assets/face/` is 3.77 MB in total —
`face_landmarker.task` (3.76 MB) plus the MediaPipe license and notice files; the OpenCV and ONNX
assets it replaced were 43 MB. The only native code is Google MediaPipe: its library is 14.4 MB for
`arm64-v8a` and 10.1 MB for `armeabi-v7a`.
Because field staff install one hand-built APK, `app/build.gradle.kts` filters to those two ARM ABIs
instead of shipping x86/x86_64 as well.

---

## 3. Web admin dashboard

A no-build static site in [`dashboard/`](dashboard). Features:

- **Overview** — present / absent / late / checked-out today, nodal visits today, total staff,
  nodal officers & kendras.
- **Attendance logs** — filter by date range, kendra, and name/code/mobile; shows check-in/out,
  total work time, status, and per-day present/absent pulse counts.
- **Nodal visits** — every nodal arrival: officer, employee code, kendra, arrival timestamp, last
  seen (last pulse) and minutes on site, with a total-time footer.
- **Staff** — enroll with name, **employee code**, mobile and **category** (staff / nodal);
  **Assign** opens the kendra list — for staff it is a radio list where the chosen kendra gets a
  shift (check-in time + day ends); for nodal it is a tick list with no timing.
- **Kendras** — create with lat/long, radius and verify interval (no timing any more); **Edit** any
  kendra later (e.g. correct its lat/long). Staff phones pick up the new location the next time
  they open the app.
- **Bulk upload** — a **Staff / Kendras** switch picks what the sheet contains. Upload an `.xlsx`
  or `.csv`, preview it, then import with per-row results. A **Download template** button generates
  the right sheet for whichever mode is selected. Upload kendras first: the staff sheet matches
  kendras by name.

### Excel template columns — Staff
| Column | Values |
|---|---|
| Full name | text |
| Employee code | unique, e.g. `EMP-0007` |
| Mobile number | 10-digit |
| Role | `staff` or `admin` |
| Category | `staff` or `nodal` |
| Kendra name(s) | existing kendra name; comma-separated for `nodal` |
| Check-in time | optional, `HH:MM` — staff only |
| Day ends | optional, `HH:MM` — staff only |

Kendras are matched **by name** and must already exist. Rows with an unknown kendra or a duplicate
mobile are reported as failures; the rest still import.

### Excel template columns — Kendras
| Column | Values |
|---|---|
| Kendra name | text, must be unique (staff sheets find kendras by name) |
| Latitude | decimal degrees, −90 to 90 |
| Longitude | decimal degrees, −180 to 180 |
| Radius (m) | optional whole number, 10–5000, default `100` |
| Verify every (min) | optional whole number, 1–240, default `30` |

Each row is validated on its own: a missing name, a non-numeric or out-of-range coordinate, or a
duplicate name fails that row with its reason while the rest of the file still imports.

### Run locally
Fill your URL + anon key into `dashboard/config.js` (replacing the `__SUPABASE_URL__` /
`__SUPABASE_ANON_KEY__` placeholders), then open `dashboard/index.html` in a browser (a simple
static server works best, e.g. `python -m http.server` in the `dashboard/` folder).

To look at the design **without any Supabase project**, open `index.html?preview=1` — the dashboard
runs on sample data and saves nothing (a purple banner says so).

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
supabase/duty_flow.sql           # work_minutes, face template, 30-min buffer, late rule
supabase/face_template_v2.sql    # one-time v1 -> v2 face template upgrade
supabase/strict_monitor.sql      # day-quality flags, left-area auto check-out, check-out reason
supabase/shifts_nodal.sql        # shift per assignment, employee code, nodal category + visits
supabase/bulk_kendras.sql        # admin_bulk_kendras() — bulk kendra upload with per-row checks
supabase/face_auth_v3.sql        # device authorization, supervised enrollment, single-use challenges
supabase/tests/                 # isolated in-memory PostgreSQL authorization regression tests
dashboard/                       # no-build web admin (index.html, app.js, config.js)
app/src/main/java/com/vsk/attendance/
  MainActivity.kt                # entry, permission gate, staff-only routing
  data/{Models,SupabaseApi,SessionStore}.kt
  util/GeoUtils.kt               # Haversine + IST clock
  location/{LocationHelper,AttendanceRules}.kt
  face/{FaceTemplate,FaceGeometry,BlinkTracker,FaceInstruction,FaceCheckSession,FaceMeshSignature,FaceFrameQuality,FaceGuide,FaceCaptureCard}.kt
  ui/{MainViewModel,LoginScreen,FaceEnrollScreen,AttendanceScreen}.kt + theme/
app/src/main/assets/face/        # pinned local models, checksums, licenses and conversion provenance
app/src/test/                    # JVM template and capture-session regression tests
tools/face-harness/              # browser harness: the JVM engine, this machine's webcam, real thresholds
.github/workflows/               # build-apk.yml, deploy-dashboard.yml
```

---

## Kendra & shift settings
Kendra = a place only:

| Setting | Default | Meaning |
|---|---|---|
| `radius_m` | 100 | Geofence radius (metres) |
| `verify_interval_min` | 30 | Presence re-verification interval |

Shift timing is per **staff→kendra assignment** (set from Staff → Assign):

| Setting | Default | Meaning |
|---|---|---|
| `check_in_start` | 08:00 | IST check-in time; window closes automatically at +30 min |
| `check_in_end` | (auto) | Stored as `check_in_start + 30 min`; not editable |
| `day_end` | 18:00 | IST auto check-out deadline |

Arrivals more than 15 minutes after `check_in_start` are stored as `late`. Nodal assignments carry
no timing at all.

Staff category: **staff** = exactly one kendra on their own shift (assigning replaces it);
**nodal** = several allowed kendras, each face-checked arrival stamped as a visit.

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
- **Field staff (Android):** mobile-number login on an administrator-activated installation.
  A cryptographically random 256-bit device token authorizes staff RPCs; only its SHA-256 hash is
  stored server-side. Knowing a mobile number or staff UUID no longer authorizes access. Manual
  punches require a short-lived, one-use challenge bound to the device, action and enrollment revision.
  These credentials do not prove that an untampered device actually performed biometric verification.
- **Admins (web):** email/password via Supabase Auth, and every admin RPC calls `assert_admin()`
  which checks `auth.uid()` against the `admins` allow-list. The old spoofable `p_admin_id`
  functions were dropped. RLS locks all tables; access is only through SECURITY DEFINER functions.
- GPS can be spoofed on a rooted device; for high assurance add Play Integrity attestation.

---

## Verification and limits

- JVM face tests: `gradle testDebugUnitTest` with the Android toolchain. One hundred and three print,
  template, geometry, blink-tracker, quality-gate and capture-session tests also passed using the
  Kotlin 2.0.21 JVM compiler directly, without installing an Android SDK.
- SQL regressions: `npm ci --prefix supabase/tests` then `npm test --prefix supabase/tests`.
  This creates a disposable in-memory PostgreSQL database, applies all ten migrations, tests 110
  authorization/activation/challenge assertions plus 25 mesh-print assertions, and reruns migrations
  9 and 10 on both empty and populated state.
  It uses no Supabase credentials or live connections. Do not paste the test fixture into a live database.
- Browser harness: `node tools/face-harness/run.mjs` compiles the seven Android-free engine files with
  the Kotlin 2.0.21 JVM compiler and serves `http://localhost:8765`, where `tools/face-harness/index.html`
  runs MediaPipe tasks-vision 1.1.1 against this repository's own `face_landmarker.task` bytes and posts
  one 640x480 frame every 120 ms to the real `FaceCheckSession`. So the framing lines, the blink
  window, the light and sharpness gate, the shape print, `MATCH_THRESHOLD` and the stored template are
  the shipped code paths, not a reimplementation, and the page shows the same `[mesh 0.97766 needs
  0.99900]` line a debug APK shows. It is a development tool: the Register/Verify/Restart/Clear buttons
  exist only here, the phone has none. Frames go to `127.0.0.1` only and are never written to disk.
  `--check` compiles without starting the server, `?noautostart=1` lets the loop be driven from the
  console against a stubbed camera. What it cannot show is the Android camera path, the bitmap copy
  that fixed the recycled-bitmap crash, the APK assets or Supabase enrolment.
- Blink thresholds were tuned against a real laptop webcam in a browser prototype (MediaPipe
  tasks-vision 1.1.1 in the GPU delegate, 30 fps): a complete check took 9.1 s from the camera opening,
  with blink peak 0.90 against the 0.55 close threshold and zero framing rejections over 137 face
  frames. That prototype is not part of this repository; the shipped values come from it, and the
  phone flow is the same: no button, guidance runs until a blink completes the check.
- Model qualification: `face_landmarker.task` is the unmodified float16 model MediaPipe publishes for
  tasks-vision 1.1.1, its SHA-256 is pinned in `app/build.gradle.kts`, and `verifyFaceModels` runs on
  every `preBuild` so a swapped asset fails the build. `FaceGuide.kt` and the mesh print were
  signature-compiled against the actual MediaPipe 1.1.1 task classes with minimal Android stubs.
  MediaPipe ships no face-recognition task, so the print is this repository's own geometry and is
  **not** a certified biometric matcher; none of these checks is an Android APK build or an accuracy
  test.
- Dashboard activation/reset, failure/retry, narrow layouts, assignments and bulk-upload browser
  regressions passed in isolated preview mode, with production API access blocked.
- Full APK compilation, Android ARM/JNI execution, camera UI and real-person/attack validation remain
  required on physical phones. `FaceCaptureCard.kt`'s Compose half has no local compiler here (it needs
  the Compose plugin), so CI is its first real build; its `FaceAnalyzer` region and file-level helpers
  are sliced verbatim out of that same file and signature-compiled locally against Android/CameraX
  stubs plus the real MediaPipe classes, because two analyzer edits shipped broken before
  that check existed. The current CI build workflow does not run JVM tests automatically;
  run the test command as part of release validation before distributing the APK.
- Attendance tracking is **foreground only** (app open). Working with the app closed needs Android
  background-location permission + a foreground service + battery-optimization handling.
- Backend tests do not cover simultaneous multi-client transactions or live Supabase deployment.
  Automatic day-end checkout currently supports same-day shifts only.
