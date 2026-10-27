/* Dhan Kharidi Samiksha — admin dashboard logic (no build step). */
(() => {
  const cfg = window.APP_CONFIG || {};
  const $ = (sel) => document.querySelector(sel);
  const $$ = (sel) => Array.from(document.querySelectorAll(sel));
  const PREVIEW = new URLSearchParams(location.search).get("preview") === "1";

  // ---------- helpers ----------
  function toast(msg, type = "info") {
    const el = document.createElement("div");
    const colors = { ok: "bg-green-600", err: "bg-red-600", info: "bg-blue-600" };
    el.className = `rounded-xl px-4 py-3 text-sm font-medium text-white shadow-xl ${colors[type] || colors.info}`;
    el.textContent = msg;
    $("#toasts").appendChild(el);
    setTimeout(() => el.remove(), 4000);
  }
  function veil(on) {
    $("#veil").classList.toggle("hidden", !on);
    $("#veil").classList.toggle("flex", !!on);
  }
  function busy(btn, on, label) {
    if (!btn) return;
    if (on) {
      btn.dataset.html = btn.innerHTML;
      btn.disabled = true;
      btn.innerHTML = `<span class="inline-block h-4 w-4 shrink-0 animate-spin rounded-full border-2 border-current border-r-transparent" aria-hidden="true"></span><span>${esc(label || "Working…")}</span>`;
    } else {
      btn.disabled = false;
      if (btn.dataset.html) btn.innerHTML = btn.dataset.html;
    }
  }
  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  function todayStr(offsetDays = 0) {
    const d = new Date(); d.setDate(d.getDate() + offsetDays);
    return d.toISOString().slice(0, 10);
  }
  function fmtWork(m) {
    if (m === null || m === undefined) return "—";
    return m < 60 ? `${m}m` : `${Math.floor(m / 60)}h ${m % 60}m`;
  }
  function statusBadge(s) {
    if (!s) return "—";
    const map = { present: "bg-green-100 text-green-700", late: "bg-amber-100 text-amber-700" };
    return `<span class="inline-block whitespace-nowrap rounded-full px-2.5 py-1 text-[11px] font-bold tracking-wide ${map[s] || "bg-slate-100 text-slate-600"}">${esc(s)}</span>`;
  }
  function flagsBadges(r) {
    const out = [];
    if (r.check_out_reason === "left_area")
      out.push(`<span class="inline-block whitespace-nowrap rounded-full px-2.5 py-1 text-[11px] font-bold tracking-wide bg-red-600 text-white">LEFT AREA</span>`);
    if ((r.absent_streak_min ?? 0) >= 15)
      out.push(`<span class="inline-block whitespace-nowrap rounded-full px-2.5 py-1 text-[11px] font-bold tracking-wide bg-red-100 text-red-700">OUT ${r.absent_streak_min}m</span>`);
    if ((r.silence_min ?? 0) > 60)
      out.push(`<span class="inline-block whitespace-nowrap rounded-full px-2.5 py-1 text-[11px] font-bold tracking-wide bg-amber-100 text-amber-700">DARK ${r.silence_min}m</span>`);
    return out.length ? out.join(" ") : `<span class="text-slate-300">—</span>`;
  }
  // Excel cells arrive as "08:00", a Date, or a fraction of a day.
  function normTime(v) {
    if (v === null || v === undefined || v === "") return "";
    if (v instanceof Date) {
      return `${String(v.getHours()).padStart(2, "0")}:${String(v.getMinutes()).padStart(2, "0")}`;
    }
    const s = String(v).trim();
    const m = s.match(/^(\d{1,2}):(\d{2})/);
    if (m) return `${m[1].padStart(2, "0")}:${m[2]}`;
    const n = parseFloat(s);
    if (!isNaN(n) && n >= 0 && n < 1) {
      const mins = Math.round(n * 24 * 60);
      return `${String(Math.floor(mins / 60) % 24).padStart(2, "0")}:${String(mins % 60).padStart(2, "0")}`;
    }
    return "";
  }

  // ---------- config guard ----------
  const hasConfig = cfg.supabaseUrl && !cfg.supabaseUrl.includes("__SUPABASE_URL__") &&
                    cfg.supabaseAnonKey && !cfg.supabaseAnonKey.includes("__SUPABASE_ANON_KEY__");
  if (!hasConfig && !PREVIEW) {
    $("#loginView").classList.remove("hidden");
    $("#loginView").classList.add("flex");
    const w = $("#loginConfigWarn");
    w.classList.remove("hidden");
    w.textContent = "Supabase is not configured. Set SUPABASE_URL and SUPABASE_ANON_KEY as repository secrets (deployed builds inject them automatically), or edit dashboard/config.js for local testing.";
    return;
  }
  if (PREVIEW) $("#previewBanner").classList.remove("hidden");

  const sb = PREVIEW ? previewClient() : window.supabase.createClient(cfg.supabaseUrl, cfg.supabaseAnonKey);
  let KENDRAS = [];
  let currentAssign = null;
  let adminVerified = false;
  let adminUserId = null;
  let authEpoch = 0;
  let currentActivation = null;
  let activationPending = false;
  let activationScrollLocked = false;

  // ---------- auth ----------
  function showLogin() {
    adminVerified = false;
    adminUserId = null;
    authEpoch++;
    closeFaceActivation(false);
    $("#appView").classList.add("hidden");
    $("#appView").classList.remove("md:flex");
    $("#loginView").classList.remove("hidden");
    $("#loginView").classList.add("flex");
  }
  async function showApp(session) {
    const epoch = ++authEpoch;
    adminVerified = false;
    closeFaceActivation(false);
    try {
      // Verify restored sessions too; the issuing RPC also enforces admin access.
      const { data, error } = await sb.rpc("admin_overview");
      if (epoch !== authEpoch) return;
      if (error || !data?.ok) throw new Error("Admin access required");
      adminUserId = session.user.id;
      adminVerified = true;
    } catch {
      if (epoch !== authEpoch) return;
      showLogin();
      toast("Unable to verify administrator access. Please sign in again.", "err");
      return;
    }
    $("#loginView").classList.add("hidden");
    $("#loginView").classList.remove("flex");
    $("#appView").classList.remove("hidden");
    $("#appView").classList.add("md:flex");
    $("#adminEmail").textContent = session.user.email;
    $("#todayLabel").textContent = new Date().toDateString();
    await switchView("overview");
  }

  $("#loginForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const btn = $("#loginBtn");
    busy(btn, true, "Signing in…");
    const { data, error } = await sb.auth.signInWithPassword({
      email: $("#loginEmail").value.trim(),
      password: $("#loginPassword").value,
    });
    busy(btn, false);
    if (error) { toast(error.message, "err"); return; }
    // verify this auth user is an allow-listed admin
    const { error: ovErr } = await sb.rpc("admin_overview");
    if (ovErr) {
      toast("Signed in, but this account is not registered as an admin.", "err");
      await sb.auth.signOut();
      return;
    }
    toast("Welcome back", "ok");
    showApp(data.session);
  });

  $("#logoutBtn").addEventListener("click", async () => {
    // Clear sensitive modal content immediately, even if sign-out is slow.
    showLogin();
    try {
      const result = await sb.auth.signOut();
      if (result?.error) throw new Error("Sign-out failed");
      toast("Signed out", "info");
    } catch {
      toast("Sign-out could not be completed. Please retry after signing in.", "err");
    }
  });

  // ---------- navigation ----------
  const TITLES = {
    overview: "Overview", logs: "Attendance logs", nodal: "Nodal visits",
    staff: "Staff", kendra: "Kendras", bulk: "Bulk upload",
  };
  async function switchView(view) {
    $$("[data-view]").forEach((a) => {
      if (a.dataset.view === view) a.setAttribute("aria-current", "page");
      else a.removeAttribute("aria-current");
    });
    $$("[data-panel]").forEach((p) => p.classList.toggle("hidden", p.dataset.panel !== view));
    $("#viewTitle").textContent = TITLES[view] || "";
    if (view === "overview") await loadOverview();
    if (view === "logs") { await ensureKendras(); fillKendraFilter("#logKendra"); await loadLogs(); }
    if (view === "nodal") { await ensureKendras(); fillKendraFilter("#nvKendra"); await loadNodal(); }
    if (view === "staff") { await ensureKendras(); await loadStaff(); }
    if (view === "kendra") await loadKendras();
  }
  $$("[data-view]").forEach((a) => a.addEventListener("click", () => switchView(a.dataset.view)));

  // ---------- data ----------
  async function ensureKendras() {
    const { data } = await sb.rpc("admin_list_kendras");
    KENDRAS = (data && data.rows) || [];
  }

  const ICONS = {
    check: `<svg fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.4"><path stroke-linecap="round" stroke-linejoin="round" d="M5 13l4 4L19 7"/></svg>`,
    x: `<svg fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.4"><path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12"/></svg>`,
    clock: `<svg fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M12 8v4l3 2m6-2a9 9 0 11-18 0 9 9 0 0118 0z"/></svg>`,
    out: `<svg fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1"/></svg>`,
    map: `<svg fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M9 20l-5.447-2.724A1 1 0 013 16.382V5.618a1 1 0 011.447-.894L9 7m0 13l6-3m-6 3V7m6 10l4.553 2.276A1 1 0 0021 18.382V7.618a1 1 0 00-.553-.894L15 4m0 13V4M9 7l6-3"/></svg>`,
    users: `<svg fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M17 20h5v-2a4 4 0 00-3-3.87M9 20H4v-2a4 4 0 013-3.87m6-1.13a4 4 0 10-4-4 4 4 0 004 4zm6-4a3 3 0 11-3-3"/></svg>`,
    walk: `<svg fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M13 5a2 2 0 11-4 0 2 2 0 014 0zM11 9l-2 4 2 2 1 5m-1-11l3 3 3 1M9 13l-3 1"/></svg>`,
    pin: `<svg fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M12 21s7-6.3 7-11a7 7 0 10-14 0c0 4.7 7 11 7 11z"/><circle cx="12" cy="10" r="2.5"/></svg>`,
  };
  const TONES = {
    green: ["bg-green-50", "text-green-600"], red: ["bg-red-50", "text-red-600"],
    amber: ["bg-amber-50", "text-amber-600"], slate: ["bg-slate-100", "text-slate-600"],
    violet: ["bg-violet-50", "text-violet-600"], blue: ["bg-blue-50", "text-blue-600"],
    indigo: ["bg-indigo-50", "text-indigo-600"], cyan: ["bg-cyan-50", "text-cyan-600"],
  };

  async function loadOverview() {
    veil(true);
    const { data, error } = await sb.rpc("admin_overview");
    veil(false);
    if (error) { toast(error.message, "err"); return; }
    const cards = [
      ["Present today", data.present_today, "green", ICONS.check],
      ["Absent today", data.absent_today, "red", ICONS.x],
      ["Late today", data.late_today, "amber", ICONS.clock],
      ["Checked out", data.checked_out_today, "slate", ICONS.out],
      ["Nodal visits today", data.nodal_visits_today, "violet", ICONS.map],
      ["Staff", data.total_staff, "blue", ICONS.users],
      ["Nodal officers", data.total_nodal, "indigo", ICONS.walk],
      ["Kendras", data.total_kendras, "cyan", ICONS.pin],
    ];
    $("#statCards").innerHTML = cards.map(([cap, val, tone, icon]) => {
      const [bg, fg] = TONES[tone];
      return `<div class="flex min-w-0 flex-col items-start gap-3 rounded-2xl border border-slate-200 bg-white p-4 shadow-sm sm:flex-row sm:items-center">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl [&>svg]:h-5 [&>svg]:w-5 ${bg} ${fg}">${icon}</div>
        <div class="min-w-0">
          <div class="text-2xl font-extrabold leading-tight tracking-tight ${fg}">${val ?? 0}</div>
          <div class="break-words text-[11px] font-semibold uppercase tracking-wide text-slate-500">${cap}</div>
        </div>
      </div>`;
    }).join("");
  }

  function fillKendraFilter(sel) {
    const el = $(sel);
    const cur = el.value;
    el.innerHTML = `<option value="">All</option>` +
      KENDRAS.map((k) => `<option value="${k.id}">${esc(k.name)}</option>`).join("");
    el.value = cur;
  }

  // ---------- attendance logs ----------
  async function loadLogs() {
    veil(true);
    const args = {
      p_from: $("#logFrom").value || todayStr(-6),
      p_to: $("#logTo").value || todayStr(0),
      p_kendra_id: $("#logKendra").value || null,
      p_search: $("#logSearch").value.trim() || null,
    };
    const { data, error } = await sb.rpc("admin_attendance_log", args);
    veil(false);
    if (error) { toast(error.message, "err"); return; }
    const rows = (data && data.rows) || [];
    if (!rows.length) { $("#logsTable").innerHTML = `<tr><td class="p-6 text-slate-500">No attendance in this range.</td></tr>`; return; }
    $("#logsTable").innerHTML = `
      <thead><tr>
        <th scope="col">Date</th><th scope="col">Staff</th><th scope="col">Code</th><th scope="col">Mobile</th><th scope="col">Kendra</th>
        <th scope="col">Check-in</th><th scope="col">Check-out</th><th scope="col">Work time</th><th scope="col">Status</th><th scope="col">Present</th><th scope="col">Absent</th><th scope="col">Flags</th>
      </tr></thead>
      <tbody>${rows.map((r) => `
        <tr>
          <td class="tabular-nums whitespace-nowrap">${esc(r.day)}</td>
          <td class="font-medium">${esc(r.staff_name)}</td>
          <td class="tabular-nums whitespace-nowrap text-slate-500">${esc(r.employee_code || "—")}</td>
          <td class="tabular-nums whitespace-nowrap">${esc(r.mobile)}</td>
          <td>${esc(r.kendra || "—")}</td>
          <td class="tabular-nums whitespace-nowrap">${esc(r.check_in_at || "—")}</td>
          <td class="tabular-nums whitespace-nowrap">${esc(r.check_out_at || "—")}</td>
          <td class="tabular-nums whitespace-nowrap">${fmtWork(r.work_minutes)}</td>
          <td>${statusBadge(r.status)}</td>
          <td class="text-green-600 font-semibold">${r.present_count ?? 0}</td>
          <td class="${(r.absent_count ?? 0) > 0 ? "text-red-600 font-semibold" : "text-slate-300"}">${r.absent_count ?? 0}</td>
          <td>${flagsBadges(r)}</td>
        </tr>`).join("")}</tbody>`;
  }
  $("#logApply").addEventListener("click", loadLogs);

  // ---------- nodal visit report ----------
  async function loadNodal() {
    veil(true);
    const args = {
      p_from: $("#nvFrom").value || todayStr(-6),
      p_to: $("#nvTo").value || todayStr(0),
      p_kendra_id: $("#nvKendra").value || null,
      p_search: $("#nvSearch").value.trim() || null,
    };
    const { data, error } = await sb.rpc("admin_nodal_visits", args);
    veil(false);
    if (error) { toast(error.message, "err"); return; }
    const rows = (data && data.rows) || [];
    if (!rows.length) { $("#nodalTable").innerHTML = `<tr><td class="p-6 text-slate-500">No nodal visits in this range.</td></tr>`; return; }
    const totalMin = rows.reduce((s, r) => s + (r.minutes_at_site || 0), 0);
    $("#nodalTable").innerHTML = `
      <thead><tr>
        <th scope="col">Date</th><th scope="col">Officer</th><th scope="col">Code</th><th scope="col">Kendra</th>
        <th scope="col">Arrived</th><th scope="col">Last seen</th><th scope="col">Time on site</th><th scope="col">Pulses</th>
      </tr></thead>
      <tbody>${rows.map((r) => `
        <tr>
          <td class="tabular-nums whitespace-nowrap">${esc(r.day)}</td>
          <td class="font-medium">${esc(r.staff_name)}</td>
          <td class="tabular-nums whitespace-nowrap text-slate-500">${esc(r.employee_code || "—")}</td>
          <td>${esc(r.kendra)}</td>
          <td class="tabular-nums whitespace-nowrap font-semibold">${esc(r.arrived_at || "—")}</td>
          <td class="tabular-nums whitespace-nowrap">${esc(r.last_seen_at || "—")}</td>
          <td class="tabular-nums whitespace-nowrap">${fmtWork(r.minutes_at_site)}</td>
          <td class="${(r.pulses_inside ?? 0) > 0 ? "text-green-600 font-semibold" : "text-red-600 font-semibold"}">${r.pulses_inside ?? 0}</td>
        </tr>`).join("")}
        <tr class="bg-slate-50">
          <td colspan="6" class="font-bold text-right">Total time on site</td>
          <td class="tabular-nums whitespace-nowrap font-bold">${fmtWork(totalMin)}</td><td></td>
        </tr></tbody>`;
  }
  $("#nvApply").addEventListener("click", loadNodal);

  // ---------- staff ----------
  async function loadStaff() {
    veil(true);
    const { data, error } = await sb.rpc("admin_list_staff");
    veil(false);
    if (error) { toast(error.message, "err"); return; }
    const rows = (data && data.rows) || [];
    if (!rows.length) { $("#staffTable").innerHTML = `<tr><td class="p-6 text-slate-500">No staff yet.</td></tr>`; return; }
    $("#staffTable").innerHTML = `
      <thead><tr><th scope="col">Name</th><th scope="col">Code</th><th scope="col">Mobile</th><th scope="col">Category</th><th scope="col">Face</th><th scope="col">Phone</th><th scope="col">Kendra &amp; shift</th><th scope="col">Actions</th></tr></thead>
      <tbody>${rows.map((r) => `
        <tr>
          <td class="font-medium">${esc(r.full_name)}</td>
          <td class="tabular-nums whitespace-nowrap text-slate-500">${esc(r.employee_code || "—")}</td>
          <td class="tabular-nums whitespace-nowrap">${esc(r.mobile_number)}</td>
          <td>${r.category === "nodal"
            ? `<span class="inline-block whitespace-nowrap rounded-full px-2.5 py-1 text-[11px] font-bold tracking-wide bg-violet-100 text-violet-700">NODAL</span>`
            : `<span class="inline-block whitespace-nowrap rounded-full px-2.5 py-1 text-[11px] font-bold tracking-wide bg-blue-100 text-blue-700">STAFF</span>`}${
            r.role === "admin" ? ` <span class="inline-block whitespace-nowrap rounded-full px-2.5 py-1 text-[11px] font-bold tracking-wide bg-slate-100 text-slate-600">ADMIN</span>` : ""}</td>
          <td>${r.face_version === 3 && r.face_registered
            ? `<span class="inline-block whitespace-nowrap rounded-full px-2.5 py-1 text-[11px] font-bold tracking-wide bg-green-100 text-green-700">Registered · v3</span>`
            : r.face_version > 0 && r.face_version < 3
              ? `<span class="inline-block whitespace-nowrap rounded-full px-2.5 py-1 text-[11px] font-bold tracking-wide bg-amber-100 text-amber-700">Upgrade needed · v${esc(r.face_version)}</span>`
              : `<span class="inline-block whitespace-nowrap rounded-full px-2.5 py-1 text-[11px] font-bold tracking-wide bg-amber-100 text-amber-700">Enrollment pending</span>`}</td>
          <td><span class="inline-block whitespace-nowrap rounded-full px-2.5 py-1 text-[11px] font-bold tracking-wide ${r.device_activated ? "bg-green-100 text-green-700" : "bg-slate-100 text-slate-600"}">${r.device_activated ? "Activated" : "Not activated"}</span></td>
          <td class="text-slate-500">${esc(r.kendra_names || "—")}</td>
          <td>${r.role !== "admin"
            ? `<div class="flex flex-wrap gap-2"><button type="button" data-assign='${esc(r.id)}' data-name='${esc(r.full_name)}' data-cat='${esc(r.category || "staff")}'
                 class="inline-flex items-center gap-2 rounded px-1 py-1 text-sm font-semibold text-blue-600 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 disabled:cursor-not-allowed disabled:opacity-60">Assign</button>
               <button type="button" data-activate='${esc(r.id)}' aria-label="Activate phone for ${esc(r.full_name)}" aria-haspopup="dialog" aria-controls="faceActivationModal"
                 class="inline-flex items-center gap-2 rounded px-1 py-1 text-sm font-semibold text-blue-600 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 disabled:cursor-not-allowed disabled:opacity-60">Activate phone</button></div>`
            : ""}</td>
        </tr>`).join("")}</tbody>`;
    $$("#staffTable [data-assign]").forEach((b) =>
      b.addEventListener("click", () => openAssign(b.dataset.assign, b.dataset.name, b.dataset.cat)));
    $$("#staffTable [data-activate]").forEach((b) =>
      b.addEventListener("click", () => openFaceActivation(rows.find((r) => r.id === b.dataset.activate), b)));
  }

  // ---------- administrator-only phone activation ----------
  function clearFaceActivationResult() {
    $("#faceActivationResult").classList.add("hidden");
    ["#faceActivationCode", "#faceActivationExpiry", "#faceActivationOutcome", "#faceActivationStatus", "#faceActivationError"].forEach((s) => { $(s).textContent = ""; });
    $("#faceActivationError").classList.add("hidden");
  }

  function updateFaceActivationControls() {
    const reset = $("#faceActivationReset").checked;
    const locked = activationPending || !!currentActivation?.issued;
    $("#faceResetConfirmWrap").classList.toggle("hidden", !reset);
    $$("#faceActivationForm input").forEach((input) => { input.disabled = locked; });
    $("#faceActivationIssue").disabled = locked || (reset && !$("#faceResetConfirm").checked);
    if (!activationPending) $("#faceActivationIssue").textContent = reset ? "Issue code with face reset" : "Issue activation code";
  }

  function openFaceActivation(staff, trigger) {
    if (!adminVerified || !staff || staff.role === "admin" || $("#appView").classList.contains("hidden")) return;
    closeFaceActivation(false);
    currentActivation = { id: staff.id, trigger, issued: false };
    $("#faceActivationForm").reset();
    clearFaceActivationResult();
    $("#faceActivationName").textContent = `${staff.full_name} · ${staff.employee_code || "No employee code"} · ${staff.mobile_number}`;
    $("#faceActivationPreview").classList.toggle("hidden", !PREVIEW);
    updateFaceActivationControls();
    activationScrollLocked = document.body.classList.contains("overflow-hidden");
    document.body.classList.add("overflow-hidden");
    $("#appView").inert = true;
    const modal = $("#faceActivationModal");
    modal.classList.remove("hidden"); modal.classList.add("flex");
    $("#faceActivationTitle").focus();
  }

  function closeFaceActivation(returnFocus = true) {
    const previous = currentActivation;
    currentActivation = null; // Late RPC responses must never repopulate a closed/reopened dialog.
    clearFaceActivationResult();
    $("#faceActivationName").textContent = "";
    $("#faceActivationForm").reset();
    const modal = $("#faceActivationModal");
    modal.classList.add("hidden"); modal.classList.remove("flex");
    if (!previous) return;
    $("#appView").inert = false;
    if (!activationScrollLocked) document.body.classList.remove("overflow-hidden");
    if (returnFocus) {
      const target = previous.trigger.isConnected ? previous.trigger : $('[data-view="staff"]');
      target.focus();
    }
  }

  $("#faceActivationClose").addEventListener("click", () => closeFaceActivation());
  $("#faceActivationDone").addEventListener("click", () => closeFaceActivation());
  $$('#faceActivationForm input[name="faceActivationMode"]').forEach((input) => input.addEventListener("change", () => {
    $("#faceResetConfirm").checked = false;
    updateFaceActivationControls();
  }));
  $("#faceResetConfirm").addEventListener("change", updateFaceActivationControls);
  document.addEventListener("keydown", (e) => {
    if (!currentActivation) return;
    if (e.key === "Escape") { e.preventDefault(); closeFaceActivation(); return; }
    if (e.key !== "Tab") return;
    const items = $$('#faceActivationModal button:not(:disabled), #faceActivationModal input:not(:disabled)').filter((el) => el.getClientRects().length);
    const first = items[0], last = items[items.length - 1];
    if (e.shiftKey && (document.activeElement === first || !items.includes(document.activeElement))) {
      e.preventDefault(); last.focus();
    } else if (!e.shiftKey && (document.activeElement === last || !items.includes(document.activeElement))) {
      e.preventDefault(); first.focus();
    }
  });
  document.addEventListener("focusin", (e) => {
    if (currentActivation && !$("#faceActivationModal").contains(e.target)) $("#faceActivationTitle").focus();
  });

  $("#faceActivationForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const activation = currentActivation;
    if (!adminVerified || !activation || activationPending || activation.issued) return;
    const resetFace = $("#faceActivationReset").checked;
    if (resetFace && !$("#faceResetConfirm").checked) {
      toast("Confirm the face reset before issuing its code.", "err");
      $("#faceResetConfirm").focus();
      return;
    }
    activationPending = true;
    clearFaceActivationResult();
    const btn = $("#faceActivationIssue");
    busy(btn, true, "Issuing…");
    updateFaceActivationControls();
    $("#faceActivationForm").setAttribute("aria-busy", "true");
    veil(true);
    try {
      const { data, error } = await sb.rpc("admin_issue_face_activation", {
        p_staff_id: activation.id, p_reset_face: resetFace,
      });
      if (currentActivation !== activation || !adminVerified) return;
      const expiry = new Date(data?.expires_at);
      if (error || data?.ok !== true || !/^[0-9a-f]{12}$/i.test(data.activation_code || "") ||
          typeof data.expires_at !== "string" || !Number.isFinite(expiry.getTime())) {
        throw new Error("Activation issue failed");
      }
      activation.issued = true;
      // The code lives only in this open admin dialog: never toast, store or log it.
      $("#faceActivationCode").textContent = data.activation_code;
      $("#faceActivationExpiry").textContent = PREVIEW
        ? `Fake expiry (preview only): ${data.expires_at}`
        : `Expires: ${expiry.toLocaleString()} (this browser's local time)`;
      $("#faceActivationOutcome").textContent = resetFace
        ? "Face reset selected: new face enrollment is required only AFTER successful code redemption. Until then, the current phone and face remain unchanged."
        : "No face reset requested. Successful code redemption replaces the current phone; issuing this code alone changes neither the phone nor the face.";
      $("#faceActivationStatus").textContent = PREVIEW ? "Dummy preview code ready — not valid for activation." : "Activation code issued. Share it privately with this verified employee.";
      $("#faceActivationResult").classList.remove("hidden");
      toast(PREVIEW ? "Dummy preview code ready in the dialog." : "Activation code ready in the dialog.", "ok");
      $("#faceActivationResult").focus();
    } catch {
      if (currentActivation !== activation || !adminVerified) return;
      // Do not echo server payloads/errors: they could contain sensitive data.
      const message = "Could not issue an activation code. Check your connection and administrator access, then retry.";
      $("#faceActivationError").textContent = message;
      $("#faceActivationError").classList.remove("hidden");
      toast(message, "err");
      $("#faceActivationError").focus();
    } finally {
      activationPending = false;
      busy(btn, false);
      $("#faceActivationForm").setAttribute("aria-busy", "false");
      updateFaceActivationControls();
      veil(false);
    }
  });

  $("#enrollForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const btn = e.target.querySelector("button");
    busy(btn, true, "Adding…");
    const { data, error } = await sb.rpc("admin_enroll_staff", {
      p_full_name: $("#enName").value.trim(),
      p_mobile: $("#enMobile").value.trim(),
      p_employee_code: $("#enCode").value.trim(),
      p_role: "staff",
      p_category: $("#enCategory").value,
    });
    busy(btn, false);
    if (error) { toast(error.message, "err"); return; }
    if (data && !data.ok) { toast(data.error || "Failed", "err"); return; }
    toast($("#enCategory").value === "nodal"
      ? "Nodal officer added — now assign the kendras they may visit."
      : "Staff added — now assign their kendra and shift.", "ok");
    e.target.reset();
    await loadStaff();
  });

  // ---------- kendras ----------
  async function loadKendras() {
    veil(true);
    const { data, error } = await sb.rpc("admin_list_kendras");
    veil(false);
    if (error) { toast(error.message, "err"); return; }
    KENDRAS = (data && data.rows) || [];
    if (!KENDRAS.length) { $("#kendraTable").innerHTML = `<tr><td class="p-6 text-slate-500">No kendras yet.</td></tr>`; return; }
    $("#kendraTable").innerHTML = `
      <thead><tr><th scope="col">Name</th><th scope="col">Lat</th><th scope="col">Long</th><th scope="col">Radius</th><th scope="col">Verify</th><th scope="col">Staff</th><th scope="col">Nodal</th><th scope="col"></th></tr></thead>
      <tbody>${KENDRAS.map((k) => `
        <tr>
          <td class="font-medium">${esc(k.name)}</td>
          <td class="tabular-nums whitespace-nowrap">${k.latitude}</td><td class="tabular-nums whitespace-nowrap">${k.longitude}</td>
          <td class="tabular-nums whitespace-nowrap">${k.radius_m} m</td><td class="tabular-nums whitespace-nowrap">every ${k.verify_interval_min} min</td>
          <td><span class="inline-block whitespace-nowrap rounded-full px-2.5 py-1 text-[11px] font-bold tracking-wide bg-blue-50 text-blue-700">${k.staff_count ?? 0}</span></td>
          <td><span class="inline-block whitespace-nowrap rounded-full px-2.5 py-1 text-[11px] font-bold tracking-wide bg-violet-50 text-violet-700">${k.nodal_count ?? 0}</span></td>
          <td><button data-kedit="${k.id}" class="inline-flex items-center gap-2 rounded px-1 py-1 text-sm font-semibold text-blue-600 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 disabled:cursor-not-allowed disabled:opacity-60">Edit</button></td>
        </tr>`).join("")}</tbody>`;
    $$("#kendraTable [data-kedit]").forEach((b) =>
      b.addEventListener("click", () => openKendraEdit(b.dataset.kedit)));
  }

  $("#kendraForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const btn = e.target.querySelector("button");
    busy(btn, true, "Creating…");
    const { data, error } = await sb.rpc("admin_create_kendra", {
      p_name: $("#kName").value.trim(),
      p_lat: parseFloat($("#kLat").value),
      p_long: parseFloat($("#kLon").value),
      p_radius_m: parseInt($("#kRadius").value || "100", 10),
      p_verify_interval_min: parseInt($("#kVerify").value || "30", 10),
    });
    busy(btn, false);
    if (error) { toast(error.message, "err"); return; }
    if (data && !data.ok) { toast(data.error || "Failed", "err"); return; }
    toast("Kendra created — assign staff to it from the Staff tab.", "ok");
    e.target.reset();
    await loadKendras();
  });

  // ---------- edit kendra modal ----------
  let currentKendraEdit = null;
  function openKendraEdit(id) {
    const k = KENDRAS.find((x) => x.id === id);
    if (!k) return;
    currentKendraEdit = id;
    $("#keName").value = k.name;
    $("#keLat").value = k.latitude;
    $("#keLon").value = k.longitude;
    $("#keRadius").value = k.radius_m;
    $("#keVerify").value = k.verify_interval_min;
    const m = $("#kendraEditModal");
    m.classList.remove("hidden"); m.classList.add("flex");
  }
  function closeKendraEdit() {
    const m = $("#kendraEditModal"); m.classList.add("hidden"); m.classList.remove("flex");
  }
  $("#kendraEditClose").addEventListener("click", closeKendraEdit);
  $("#kendraEditCancel").addEventListener("click", closeKendraEdit);

  $("#kendraEditForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    if (!currentKendraEdit) return;
    const btn = $("#kendraEditSave");
    busy(btn, true, "Saving…");
    const { data, error } = await sb.rpc("admin_update_kendra", {
      p_kendra_id: currentKendraEdit,
      p_name: $("#keName").value.trim(),
      p_latitude: parseFloat($("#keLat").value),
      p_longitude: parseFloat($("#keLon").value),
      p_radius_m: parseInt($("#keRadius").value, 10),
      p_verify_interval_min: parseInt($("#keVerify").value, 10),
    });
    busy(btn, false);
    if (error) { toast(error.message, "err"); return; }
    if (data && !data.ok) { toast(data.error || "Failed", "err"); return; }
    toast("Kendra updated. Staff phones pick up the new location when they next open the app.", "ok");
    closeKendraEdit();
    await loadKendras();
  });

  // ---------- assign kendra + shift modal ----------
  async function openAssign(staffId, name, category) {
    currentAssign = { id: staffId, name, category };
    $("#assignName").textContent = name;
    $("#assignCategory").textContent = category === "nodal"
      ? "Nodal officer — may visit several kendras"
      : "Staff — reports to one kendra on a shift";
    $("#assignHint").textContent = category === "nodal"
      ? "Tick every kendra this officer may visit. Nodal officers have no shift time: each face-checked arrival is stamped in the Nodal visits report and closed by the last pulse seen there."
      : "Pick the kendra this person reports to and set their shift. Check-in closes 30 minutes after the check-in time; arrivals more than 15 minutes late are flagged late. Saving a new kendra replaces the previous one.";
    veil(true);
    await ensureKendras();
    const { data } = await sb.rpc("admin_staff_kendras", { p_staff_id: staffId });
    veil(false);
    renderAssignList((data && data.assignments) || []);
    const m = $("#assignModal");
    m.classList.remove("hidden"); m.classList.add("flex");
  }

  function renderAssignList(assignments) {
    const nodal = currentAssign.category === "nodal";
    const byId = new Map(assignments.map((a) => [a.kendra_id, a]));
    if (!KENDRAS.length) {
      $("#assignList").innerHTML = `<p class="text-sm text-slate-500 p-2">No kendras exist yet — create one from the Kendras tab.</p>`;
      return;
    }
    $("#assignList").innerHTML = KENDRAS.map((k) => {
      const a = byId.get(k.id);
      if (nodal) {
        return `<div class="group rounded-xl border border-slate-200 bg-white p-3 data-[selected=true]:border-blue-300 data-[selected=true]:bg-blue-50" data-selected="${!!a}" data-row="${k.id}">
          <label class="flex flex-wrap items-center gap-3 cursor-pointer">
            <input type="checkbox" data-kid="${k.id}" ${a ? "checked" : ""} class="w-4 h-4 accent-violet-600"/>
            <span class="text-sm font-medium">${esc(k.name)}</span>
            <span class="ml-auto text-xs ${a ? "text-violet-700 font-semibold" : "text-slate-400"}">${a ? "Can visit" : "Not assigned"}</span>
          </label>
        </div>`;
      }
      return `<div class="group rounded-xl border border-slate-200 bg-white p-3 data-[selected=true]:border-blue-300 data-[selected=true]:bg-blue-50" data-selected="${!!a}" data-row="${k.id}">
        <label class="flex flex-wrap items-center gap-3 cursor-pointer">
          <input type="radio" name="assignKendra" data-kid="${k.id}" ${a ? "checked" : ""} class="w-4 h-4 accent-blue-600"/>
          <span class="text-sm font-medium">${esc(k.name)}</span>
          ${a ? `<span class="inline-block whitespace-nowrap rounded-full px-2.5 py-1 text-[11px] font-bold tracking-wide bg-green-100 text-green-700 ml-auto">Current · ${esc(a.check_in_start || "—")}–${esc(a.day_end || "—")}</span>` : ""}
        </label>
        <div class="mt-3 hidden grid-cols-2 gap-2 group-data-[selected=true]:grid">
          <div class="min-w-0"><label class="block text-[11px] font-semibold uppercase tracking-wide text-slate-500">Check-in time</label>
            <input type="time" data-start value="${(a && a.check_in_start) || "08:00"}" class="mt-1 w-full min-w-0 rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 focus:border-blue-600 focus:outline-none focus:ring-2 focus:ring-blue-100"/></div>
          <div><label class="block text-[11px] font-semibold uppercase tracking-wide text-slate-500">Day ends</label>
            <input type="time" data-end value="${(a && a.day_end) || "18:00"}" class="mt-1 w-full min-w-0 rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 focus:border-blue-600 focus:outline-none focus:ring-2 focus:ring-blue-100"/></div>
          <div class="col-span-2 flex items-center justify-between gap-2 flex-wrap">
            <span class="text-[11px] text-slate-500">Check-in closes 30 min after the check-in time.</span>
            <span class="flex gap-2">
              ${a ? `<button type="button" data-remove="${k.id}" class="inline-flex items-center justify-center gap-2 rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-semibold text-slate-700 transition hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 disabled:cursor-not-allowed disabled:opacity-60">Remove</button>` : ""}
              <button type="button" data-save="${k.id}" class="inline-flex items-center justify-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white shadow-sm transition hover:bg-blue-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-60">${a ? "Update shift" : "Assign shift"}</button>
            </span>
          </div>
        </div>
      </div>`;
    }).join("");

    if (nodal) {
      $$('#assignList input[type=checkbox]').forEach((cb) =>
        cb.addEventListener("change", () => toggleNodal(cb)));
    } else {
      $$('#assignList input[type=radio]').forEach((rb) =>
        rb.addEventListener("change", () => {
          $$("#assignList [data-row]").forEach((row) => {
            row.dataset.selected = String(row.dataset.row === rb.dataset.kid);
          });
        }));
      $$("#assignList [data-save]").forEach((b) =>
        b.addEventListener("click", () => saveShift(b.dataset.save, b)));
      $$("#assignList [data-remove]").forEach((b) =>
        b.addEventListener("click", () => removeAssignment(b.dataset.remove, b)));
    }
  }

  async function toggleNodal(cb) {
    const row = cb.closest("[data-row]");
    cb.disabled = true;
    const { data, error } = await sb.rpc("admin_assign_kendra", {
      p_staff_id: currentAssign.id, p_kendra_id: cb.dataset.kid, p_allowed: cb.checked,
      p_check_in_start: null, p_day_end: null,
    });
    cb.disabled = false;
    if (error) { toast(error.message, "err"); cb.checked = !cb.checked; return; }
    if (data && !data.ok) { toast(data.error || "Failed", "err"); cb.checked = !cb.checked; return; }
    row.dataset.selected = String(cb.checked);
    row.querySelector("span.ml-auto").textContent = cb.checked ? "Can visit" : "Not assigned";
    row.querySelector("span.ml-auto").className =
      `ml-auto text-xs ${cb.checked ? "text-violet-700 font-semibold" : "text-slate-400"}`;
    toast(cb.checked ? "Kendra added to this officer's visits" : "Kendra removed", "ok");
    await loadStaff();
  }

  async function saveShift(kendraId, btn) {
    const row = $(`#assignList [data-row="${kendraId}"]`);
    const start = row.querySelector("[data-start]").value;
    const end = row.querySelector("[data-end]").value;
    if (!start) { toast("Set the check-in time for this shift.", "err"); return; }
    busy(btn, true, "Saving…");
    const { data, error } = await sb.rpc("admin_assign_kendra", {
      p_staff_id: currentAssign.id, p_kendra_id: kendraId, p_allowed: true,
      p_check_in_start: start, p_day_end: end || null,
    });
    busy(btn, false);
    if (error) { toast(error.message, "err"); return; }
    if (data && !data.ok) { toast(data.error || "Failed", "err"); return; }
    toast(`Shift saved — check-in ${start} to ${data.check_in_end || ""}, day ends ${end || "18:00"}.`, "ok");
    const { data: fresh } = await sb.rpc("admin_staff_kendras", { p_staff_id: currentAssign.id });
    renderAssignList((fresh && fresh.assignments) || []);
    await loadStaff();
    await loadKendrasIfVisible();
  }

  async function removeAssignment(kendraId, btn) {
    busy(btn, true, "Removing…");
    const { data, error } = await sb.rpc("admin_assign_kendra", {
      p_staff_id: currentAssign.id, p_kendra_id: kendraId, p_allowed: false,
      p_check_in_start: null, p_day_end: null,
    });
    busy(btn, false);
    if (error) { toast(error.message, "err"); return; }
    if (data && !data.ok) { toast(data.error || "Failed", "err"); return; }
    toast("Assignment removed", "ok");
    const { data: fresh } = await sb.rpc("admin_staff_kendras", { p_staff_id: currentAssign.id });
    renderAssignList((fresh && fresh.assignments) || []);
    await loadStaff();
    await loadKendrasIfVisible();
  }

  async function loadKendrasIfVisible() {
    if (!$("[data-panel='kendra']").classList.contains("hidden")) await loadKendras();
  }

  $("#assignClose").addEventListener("click", () => {
    const m = $("#assignModal"); m.classList.add("hidden"); m.classList.remove("flex");
  });

  // ---------- bulk excel ----------
  const BULK_MODES = {
    staff: {
      title: "Bulk enroll staff from Excel",
      hint: `Columns: <b>Full name</b>, <b>Employee code</b>, <b>Mobile number</b>, <b>Role</b> (staff/admin), <b>Category</b> (staff/nodal), <b>Kendra name(s)</b> (comma-separated for nodal), <b>Check-in time</b> and <b>Day ends</b> (optional, for staff). Kendras must already exist — upload them first under <b>Kendras</b> above.`,
      submit: "Upload & enroll",
      done: "enrolled",
      rpc: "admin_bulk_enroll",
      sheet: "Staff",
      file: "dks-staff-template.xlsx",
      headers: ["Full name", "Employee code", "Mobile number", "Role", "Category",
                "Kendra name(s)", "Check-in time", "Day ends"],
      sample: [
        ["Ram Kumar Sahu", "EMP-0001", "9812345670", "staff", "staff", "Kendra Raipur-1", "08:00", "18:00"],
        ["Sita Verma", "EMP-0002", "9876543210", "staff", "nodal", "Kendra Raipur-1, Kendra Dhamtari-2", "", ""],
        ["Anil Yadav", "EMP-0003", "9765432109", "staff", "staff", "Kendra Dhamtari-2", "14:00", "22:00"],
      ],
      widths: [22, 14, 15, 10, 11, 40, 13, 11],
      previewMin: "",
    },
    kendra: {
      title: "Bulk create kendras from Excel",
      hint: `Columns: <b>Kendra name</b>, <b>Latitude</b>, <b>Longitude</b>, <b>Radius (m)</b> and <b>Verify every (min)</b>. The last two are optional and default to <b>100 m</b> and <b>30 min</b>. Names must be unique — the staff upload finds kendras by name. Rows with a bad latitude, longitude or duplicate name are reported individually and the rest still import.`,
      submit: "Upload & create",
      done: "created",
      rpc: "admin_bulk_kendras",
      sheet: "Kendras",
      file: "dks-kendra-template.xlsx",
      headers: ["Kendra name", "Latitude", "Longitude", "Radius (m)", "Verify every (min)"],
      sample: [
        ["Kendra Raipur-1", 21.2514, 81.6296, 150, 30],
        ["Kendra Dhamtari-2", 20.7096, 81.5519, 120, 30],
        ["Kendra Balod-3", 20.7294, 81.2743, 100, 45],
      ],
      widths: [26, 13, 13, 12, 18],
      previewMin: "720px",
    },
  };
  let bulkMode = "staff";
  let bulkBusy = false;
  let BULK_ROWS = [];

  function setBulkMode(mode) {
    if (bulkBusy) { toast("Wait for the current upload to finish.", "err"); return; }
    bulkMode = mode;
    BULK_ROWS = [];
    $("#xlsxFile").value = "";
    $("#bulkPreviewWrap").classList.add("hidden");
    $("#bulkResultWrap").classList.add("hidden");
    $$("#bulkModeBtns [data-bulk-mode]").forEach((b) =>
      b.setAttribute("aria-current", String(b.dataset.bulkMode === mode)));
    const cfg = BULK_MODES[mode];
    $("#bulkTitle").textContent = cfg.title;
    $("#bulkHint").innerHTML = cfg.hint;
    $("#bulkSubmit").textContent = cfg.submit;
    $("#bulkPreview").style.minWidth = cfg.previewMin;
  }

  $$("#bulkModeBtns [data-bulk-mode]").forEach((b) =>
    b.addEventListener("click", () => setBulkMode(b.dataset.bulkMode)));

  $("#tplBtn").addEventListener("click", () => {
    const cfg = BULK_MODES[bulkMode];
    const ws = XLSX.utils.aoa_to_sheet([cfg.headers, ...cfg.sample]);
    ws["!cols"] = cfg.widths.map((wch) => ({ wch }));
    const wb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(wb, ws, cfg.sheet);
    XLSX.writeFile(wb, cfg.file);
    toast(`${cfg.sheet} template downloaded.`, "ok");
  });

  function normKey(k) { return String(k).toLowerCase().replace(/[^a-z]/g, ""); }
  function mapRow(raw) {
    const out = { full_name: "", employee_code: "", mobile_number: "", role: "staff",
                  category: "staff", kendra_names: "", check_in_start: "", day_end: "" };
    for (const [k, v] of Object.entries(raw)) {
      const key = normKey(k);
      const val = String(v ?? "").trim();
      if (["fullname", "name"].includes(key)) out.full_name = val;
      else if (["employeecode", "code", "empcode", "employeeid"].includes(key)) out.employee_code = val.toUpperCase();
      else if (["mobilenumber", "mobile", "phone", "phonenumber"].includes(key)) out.mobile_number = val.replace(/\D/g, "");
      else if (key === "role") out.role = (val || "staff").toLowerCase();
      else if (["category", "type", "dutytype", "duty"].includes(key))
        out.category = ["nodal", "multi"].includes(val.toLowerCase()) ? "nodal" : "staff";
      else if (["kendranames", "kendraname", "kendra", "kendras"].includes(key)) out.kendra_names = val;
      else if (["checkintime", "checkin", "intime", "shiftstart", "reportingtime"].includes(key)) out.check_in_start = normTime(v);
      else if (["dayends", "dayend", "endtime", "shiftend", "dayendsat"].includes(key)) out.day_end = normTime(v);
    }
    return out;
  }
  // A coordinate can never carry a thousands separator, so a lone comma is
  // the decimal comma some regional Excel installs write.
  function coordText(v) {
    const s = String(v ?? "").trim();
    return /^[+-]?\d+,\d+$/.test(s) ? s.replace(",", ".") : s;
  }
  function wholeText(v) { return String(v ?? "").replace(/\D/g, ""); }
  function mapKendraRow(raw) {
    const out = { name: "", latitude: "", longitude: "", radius_m: "", verify_interval_min: "" };
    for (const [k, v] of Object.entries(raw)) {
      const key = normKey(k);
      const val = String(v ?? "").trim();
      if (["kendraname", "name", "kendra", "kendranames", "centrename", "centre", "center"].includes(key)) out.name = val;
      else if (["latitude", "lat"].includes(key)) out.latitude = coordText(v);
      else if (["longitude", "long", "lon", "lng"].includes(key)) out.longitude = coordText(v);
      else if (["radiusm", "radius", "radiusmetres", "radiusmeters", "fenceradius", "geofenceradius"].includes(key)) out.radius_m = wholeText(v);
      else if (["verifyeverymin", "verifyintervalmin", "verifyevery", "verifyinterval", "verify",
                "reverifymin", "pulseintervalmin", "verifyminutes"].includes(key)) out.verify_interval_min = wholeText(v);
    }
    return out;
  }

  const dash = (s) => (String(s ?? "").trim() === "" ? `<span class="text-slate-300">—</span>` : esc(s));

  function renderBulkPreview() {
    $("#bulkCount").textContent = BULK_ROWS.length;
    $("#bulkPreviewWrap").classList.toggle("hidden", BULK_ROWS.length === 0);
    $("#bulkResultWrap").classList.add("hidden");
    if (!BULK_ROWS.length) return;
    $("#bulkPreview").innerHTML = bulkMode === "kendra" ? `
      <thead><tr><th scope="col">Kendra name</th><th scope="col">Latitude</th><th scope="col">Longitude</th><th scope="col">Radius</th><th scope="col">Verify every</th></tr></thead>
      <tbody>${BULK_ROWS.map((r) => `
        <tr>
          <td class="font-medium">${dash(r.name)}</td>
          <td class="tabular-nums whitespace-nowrap">${dash(r.latitude)}</td>
          <td class="tabular-nums whitespace-nowrap">${dash(r.longitude)}</td>
          <td class="tabular-nums whitespace-nowrap">${r.radius_m ? `${esc(r.radius_m)} m` : `<span class="text-slate-400">100 m (default)</span>`}</td>
          <td class="tabular-nums whitespace-nowrap">${r.verify_interval_min ? `${esc(r.verify_interval_min)} min` : `<span class="text-slate-400">30 min (default)</span>`}</td>
        </tr>`).join("")}</tbody>` : `
      <thead><tr><th scope="col">Name</th><th scope="col">Code</th><th scope="col">Mobile</th><th scope="col">Role</th><th scope="col">Category</th><th scope="col">Kendra(s)</th><th scope="col">Check-in</th><th scope="col">Day ends</th></tr></thead>
      <tbody>${BULK_ROWS.map((r) => `
        <tr><td>${esc(r.full_name)}</td><td class="tabular-nums whitespace-nowrap">${esc(r.employee_code)}</td>
        <td class="tabular-nums whitespace-nowrap">${esc(r.mobile_number)}</td><td>${esc(r.role)}</td>
        <td>${r.category === "nodal" ? `<span class="inline-block whitespace-nowrap rounded-full px-2.5 py-1 text-[11px] font-bold tracking-wide bg-violet-100 text-violet-700">NODAL</span>` : `<span class="inline-block whitespace-nowrap rounded-full px-2.5 py-1 text-[11px] font-bold tracking-wide bg-blue-100 text-blue-700">STAFF</span>`}</td>
        <td class="text-slate-500">${esc(r.kendra_names)}</td>
        <td class="tabular-nums whitespace-nowrap">${esc(r.check_in_start || "—")}</td><td class="tabular-nums whitespace-nowrap">${esc(r.day_end || "—")}</td></tr>`).join("")}</tbody>`;
  }

  $("#xlsxFile").addEventListener("change", async (e) => {
    const file = e.target.files[0];
    if (!file) return;
    const buf = await file.arrayBuffer();
    const wb = XLSX.read(buf, { type: "array" });
    const sheet = wb.Sheets[wb.SheetNames[0]];
    const json = sheet ? XLSX.utils.sheet_to_json(sheet, { defval: "" }) : [];
    if (bulkMode === "kendra") {
      BULK_ROWS = json.map(mapKendraRow).filter((r) => r.name || r.latitude || r.longitude);
    } else {
      BULK_ROWS = json.map(mapRow).filter((r) => r.full_name || r.mobile_number || r.employee_code);
    }
    renderBulkPreview();
    if (!BULK_ROWS.length) toast("No rows found. Check the header row.", "err");
  });

  $("#bulkSubmit").addEventListener("click", async () => {
    if (!BULK_ROWS.length) { toast("Nothing to upload.", "err"); return; }
    const cfg = BULK_MODES[bulkMode];
    const btn = $("#bulkSubmit");
    bulkBusy = true;
    busy(btn, true, "Uploading…");
    const { data, error } = await sb.rpc(cfg.rpc, { p_rows: BULK_ROWS });
    busy(btn, false);
    bulkBusy = false;
    if (error) { toast(error.message, "err"); return; }
    if (data && !data.ok) { toast(data.error || "Failed", "err"); return; }
    const results = (data && data.results) || [];
    const okCount = results.filter((r) => r.ok).length;
    $("#bulkResultWrap").classList.remove("hidden");
    $("#bulkResult").innerHTML = bulkMode === "kendra" ? `
      <thead><tr><th scope="col">Kendra name</th><th scope="col">Latitude</th><th scope="col">Longitude</th><th scope="col">Result</th></tr></thead>
      <tbody>${results.map((r) => `
        <tr>
          <td class="font-medium">${dash(r.name)}</td>
          <td class="tabular-nums whitespace-nowrap">${dash(r.latitude)}</td>
          <td class="tabular-nums whitespace-nowrap">${dash(r.longitude)}</td>
          <td>${r.ok
            ? `<span class="inline-block whitespace-nowrap rounded-full px-2.5 py-1 text-[11px] font-bold tracking-wide bg-green-100 text-green-700">Created</span>`
            : `<span class="inline-block whitespace-nowrap rounded-full px-2.5 py-1 text-[11px] font-bold tracking-wide bg-red-100 text-red-700">${esc(r.error || "Failed")}</span>`}</td>
        </tr>`).join("")}</tbody>` : `
      <thead><tr><th scope="col">Name</th><th scope="col">Code</th><th scope="col">Mobile</th><th scope="col">Result</th></tr></thead>
      <tbody>${results.map((r) => `
        <tr>
          <td>${esc(r.name)}</td><td class="tabular-nums whitespace-nowrap">${esc(r.code || "—")}</td><td class="tabular-nums whitespace-nowrap">${esc(r.mobile)}</td>
          <td>${r.ok
            ? `<span class="inline-block whitespace-nowrap rounded-full px-2.5 py-1 text-[11px] font-bold tracking-wide bg-green-100 text-green-700">Enrolled</span>`
            : `<span class="inline-block whitespace-nowrap rounded-full px-2.5 py-1 text-[11px] font-bold tracking-wide bg-red-100 text-red-700">${esc(r.error || "Failed")}</span>`}</td>
        </tr>`).join("")}</tbody>`;
    toast(`${okCount} of ${results.length} ${cfg.done}.`, okCount ? "ok" : "err");
    BULK_ROWS = []; $("#xlsxFile").value = "";
    $("#bulkPreviewWrap").classList.add("hidden");
    if (bulkMode === "kendra") {
      await ensureKendras();
      fillKendraFilter("#logKendra");
      fillKendraFilter("#nvKendra");
      await loadKendrasIfVisible();
    } else {
      await loadStaff();
      await loadKendrasIfVisible();
    }
  });

  // ---------- default filter dates ----------
  ["#logFrom", "#nvFrom"].forEach((s) => { $(s).value = todayStr(-6); });
  ["#logTo", "#nvTo"].forEach((s) => { $(s).value = todayStr(0); });

  // ---------- session bootstrap ----------
  sb.auth.getSession().then(({ data }) => {
    if (data.session) showApp(data.session); else showLogin();
  });
  sb.auth.onAuthStateChange((_evt, session) => {
    if (!session || (adminVerified && session.user.id !== adminUserId)) showLogin();
  });
  window.addEventListener("pagehide", () => closeFaceActivation(false));

  // ---------- preview mode (design check without credentials) ----------
  function previewClient() {
    const wait = (ms = 160) => new Promise((r) => setTimeout(r, ms));
    const K = [
      { id: "k1", name: "Kendra Raipur-1", latitude: 21.2514, longitude: 81.6296, radius_m: 150, verify_interval_min: 30, staff_count: 6, nodal_count: 3 },
      { id: "k2", name: "Kendra Dhamtari-2", latitude: 20.7096, longitude: 81.5519, radius_m: 120, verify_interval_min: 30, staff_count: 4, nodal_count: 2 },
      { id: "k3", name: "Kendra Balod-3", latitude: 20.7294, longitude: 81.2743, radius_m: 100, verify_interval_min: 45, staff_count: 3, nodal_count: 1 },
      { id: "k4", name: "Kendra Bemetara-4", latitude: 21.5746, longitude: 81.2938, radius_m: 100, verify_interval_min: 30, staff_count: 5, nodal_count: 2 },
      { id: "k5", name: "Kendra Rajnandgaon-5", latitude: 21.0972, longitude: 81.0317, radius_m: 200, verify_interval_min: 30, staff_count: 2, nodal_count: 0 },
    ];
    const S = [
      { id: "s1", full_name: "Ram Kumar Sahu", employee_code: "EMP-0001", mobile_number: "9812345670", role: "staff", category: "staff", face_version: 3, device_activated: true, face_registered: true, kendra_names: "Kendra Raipur-1 (08:00)" },
      { id: "s2", full_name: "Sita Verma", employee_code: "EMP-0002", mobile_number: "9876543210", role: "staff", category: "nodal", face_version: 2, device_activated: false, face_registered: false, kendra_names: "Kendra Raipur-1, Kendra Dhamtari-2, Kendra Balod-3" },
      { id: "s3", full_name: "Anil Yadav", employee_code: "EMP-0003", mobile_number: "9765432109", role: "staff", category: "staff", face_version: 0, device_activated: false, face_registered: false, kendra_names: "Kendra Dhamtari-2 (14:00)" },
      { id: "s4", full_name: "Kavita Nishad", employee_code: "EMP-0004", mobile_number: "9654321098", role: "staff", category: "staff", face_version: 3, device_activated: true, face_registered: true, kendra_names: "Kendra Balod-3 (09:30)" },
      { id: "s5", full_name: "Mahesh Dewangan", employee_code: "EMP-0005", mobile_number: "9543210987", role: "staff", category: "nodal", face_version: 3, device_activated: true, face_registered: true, kendra_names: "Kendra Bemetara-4, Kendra Rajnandgaon-5" },
      { id: "s6", full_name: "Pooja Sahu", employee_code: "EMP-0006", mobile_number: "9432109876", role: "admin", category: "staff", face_version: 0, device_activated: false, face_registered: false, kendra_names: null },
    ];
    const A = {
      s1: [{ kendra_id: "k1", kendra_name: "Kendra Raipur-1", check_in_start: "08:00", check_in_end: "08:30", day_end: "18:00" }],
      s2: [{ kendra_id: "k1", kendra_name: "Kendra Raipur-1", check_in_start: null, check_in_end: null, day_end: null },
           { kendra_id: "k2", kendra_name: "Kendra Dhamtari-2", check_in_start: null, check_in_end: null, day_end: null },
           { kendra_id: "k3", kendra_name: "Kendra Balod-3", check_in_start: null, check_in_end: null, day_end: null }],
      s3: [{ kendra_id: "k2", kendra_name: "Kendra Dhamtari-2", check_in_start: "14:00", check_in_end: "14:30", day_end: "22:00" }],
      s5: [{ kendra_id: "k4", kendra_name: "Kendra Bemetara-4", check_in_start: null, check_in_end: null, day_end: null },
           { kendra_id: "k5", kendra_name: "Kendra Rajnandgaon-5", check_in_start: null, check_in_end: null, day_end: null }],
    };
    const L = [
      { day: todayStr(0), staff_name: "Ram Kumar Sahu", employee_code: "EMP-0001", mobile: "9812345670", kendra: "Kendra Raipur-1", check_in_at: "08:04:11", check_out_at: null, status: "present", work_minutes: null, present_count: 9, absent_count: 0, check_out_reason: null, absent_streak_min: 0, silence_min: 22 },
      { day: todayStr(0), staff_name: "Anil Yadav", employee_code: "EMP-0003", mobile: "9765432109", kendra: "Kendra Dhamtari-2", check_in_at: "14:31:02", check_out_at: null, status: "late", work_minutes: null, present_count: 3, absent_count: 2, check_out_reason: null, absent_streak_min: 18, silence_min: 74 },
      { day: todayStr(0), staff_name: "Kavita Nishad", employee_code: "EMP-0004", mobile: "9654321098", kendra: "Kendra Balod-3", check_in_at: "09:28:45", check_out_at: "13:10:00", status: "present", work_minutes: 221, check_out_reason: "left_area", absent_streak_min: 34, silence_min: 40 },
      { day: todayStr(-1), staff_name: "Ram Kumar Sahu", employee_code: "EMP-0001", mobile: "9812345670", kendra: "Kendra Raipur-1", check_in_at: "08:01:19", check_out_at: "18:00:04", status: "present", work_minutes: 583, check_out_reason: "day_end", absent_streak_min: 0, silence_min: 28 },
      { day: todayStr(-1), staff_name: "Anil Yadav", employee_code: "EMP-0003", mobile: "9765432109", kendra: "Kendra Dhamtari-2", check_in_at: "14:22:40", check_out_at: "22:00:11", status: "late", work_minutes: 442, check_out_reason: "manual", absent_streak_min: 12, silence_min: 96 },
    ];
    const V = [
      { day: todayStr(0), staff_name: "Sita Verma", employee_code: "EMP-0002", mobile: "9876543210", kendra: "Kendra Raipur-1", arrived_at: "09:12:44", last_seen_at: "10:41:07", minutes_at_site: 88, pulses_inside: 4 },
      { day: todayStr(0), staff_name: "Sita Verma", employee_code: "EMP-0002", mobile: "9876543210", kendra: "Kendra Dhamtari-2", arrived_at: "11:58:03", last_seen_at: "13:20:55", minutes_at_site: 82, pulses_inside: 3 },
      { day: todayStr(0), staff_name: "Mahesh Dewangan", employee_code: "EMP-0005", mobile: "9543210987", kendra: "Kendra Bemetara-4", arrived_at: "10:05:19", last_seen_at: "11:02:31", minutes_at_site: 57, pulses_inside: 2 },
      { day: todayStr(0), staff_name: "Mahesh Dewangan", employee_code: "EMP-0005", mobile: "9543210987", kendra: "Kendra Rajnandgaon-5", arrived_at: "15:44:10", last_seen_at: "15:44:10", minutes_at_site: 0, pulses_inside: 0 },
      { day: todayStr(-1), staff_name: "Sita Verma", employee_code: "EMP-0002", mobile: "9876543210", kendra: "Kendra Balod-3", arrived_at: "12:31:00", last_seen_at: "14:05:22", minutes_at_site: 94, pulses_inside: 5 },
    ];
    const ok = (extra = {}) => ({ data: Object.assign({ ok: true }, extra), error: null });
    const rows = (r) => ({ data: { ok: true, rows: r }, error: null });

    async function rpc(fn, args = {}) {
      await wait();
      switch (fn) {
        case "admin_overview":
          return { data: { ok: true, today: todayStr(0), total_staff: 42, total_nodal: 6, total_kendras: K.length,
                           present_today: 35, late_today: 4, checked_out_today: 12, absent_today: 7,
                           nodal_visits_today: 11 }, error: null };
        case "admin_list_kendras": return rows(K);
        case "admin_list_staff": return rows(S);
        case "admin_issue_face_activation":
          // Deterministic, non-secret dummy with a deliberately fake/past expiry.
          // Issuance alone must not mutate the sample phone or face status.
          return ok({ activation_code: "000000000000", expires_at: "2000-01-01T00:15:00Z" });
        case "admin_staff_kendras": return { data: { ok: true, category: (S.find((s) => s.id === args.p_staff_id) || {}).category || "staff", assignments: A[args.p_staff_id] || [] }, error: null };
        case "admin_attendance_log": return rows(L);
        case "admin_nodal_visits": return rows(V);
        case "admin_assign_kendra": return ok({ check_in_end: "08:30" });
        case "admin_bulk_enroll":
          return { data: { ok: true, results: (args.p_rows || []).map((r) => ({ name: r.full_name, code: r.employee_code, mobile: r.mobile_number, ok: true })) }, error: null };
        case "admin_bulk_kendras": {
          const results = (args.p_rows || []).map((r, i) => {
            const name = String(r.name || "").trim();
            const lat = String(r.latitude || "").trim();
            const lon = String(r.longitude || "").trim();
            const bad = !name ? "Kendra name is required."
              : K.some((k) => k.name.toLowerCase() === name.toLowerCase()) ? `Kendra name already exists: ${name}`
              : !lat ? "Latitude is required."
              : !/^[+-]?(\d+(\.\d*)?|\.\d+)$/.test(lat) ? `Latitude is not a number: ${lat}`
              : Number(lat) < -90 || Number(lat) > 90 ? `Latitude must be between -90 and 90: ${lat}`
              : !lon ? "Longitude is required."
              : !/^[+-]?(\d+(\.\d*)?|\.\d+)$/.test(lon) ? `Longitude is not a number: ${lon}`
              : Number(lon) < -180 || Number(lon) > 180 ? `Longitude must be between -180 and 180: ${lon}`
              : null;
            if (!bad) K.push({ id: `kb${i}${K.length}`, name, latitude: Number(lat), longitude: Number(lon),
                               radius_m: Number(r.radius_m) || 100, verify_interval_min: Number(r.verify_interval_min) || 30,
                               staff_count: 0, nodal_count: 0 });
            return { name, latitude: lat, longitude: lon, ok: !bad, error: bad };
          });
          return { data: { ok: true, results }, error: null };
        }
        default: return ok();
      }
    }
    return {
      auth: {
        async getSession() { return { data: { session: { user: { email: "preview@example.org" } } } }; },
        async signInWithPassword() { return { data: { session: { user: { email: "preview@example.org" } } }, error: null }; },
        async signOut() {},
        onAuthStateChange() {},
      },
      rpc,
    };
  }
})();
