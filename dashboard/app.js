/* Dhan Kharidi Samiksha — admin dashboard logic (no build step). */
(() => {
  const cfg = window.APP_CONFIG || {};
  const $ = (sel) => document.querySelector(sel);
  const $$ = (sel) => Array.from(document.querySelectorAll(sel));

  // ---------- helpers ----------
  function toast(msg, type = "info") {
    const el = document.createElement("div");
    el.className = `toast ${type}`;
    el.textContent = msg;
    $("#toasts").appendChild(el);
    setTimeout(() => el.remove(), 4000);
  }
  function veil(on) { $("#veil").classList.toggle("on", !!on); }
  function busy(btn, on, label) {
    if (!btn) return;
    if (on) {
      btn.dataset.html = btn.innerHTML;
      btn.disabled = true;
      btn.innerHTML = `<span class="spinner"></span><span>${label || "Working…"}</span>`;
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

  // ---------- config guard ----------
  const hasConfig = cfg.supabaseUrl && !cfg.supabaseUrl.includes("__SUPABASE_URL__") &&
                    cfg.supabaseAnonKey && !cfg.supabaseAnonKey.includes("__SUPABASE_ANON_KEY__");
  if (!hasConfig) {
    $("#loginView").classList.remove("hidden");
    $("#loginView").classList.add("flex");
    const w = $("#loginConfigWarn");
    w.classList.remove("hidden");
    w.textContent = "Supabase is not configured. Set SUPABASE_URL and SUPABASE_ANON_KEY as repository secrets (deployed builds inject them automatically), or edit dashboard/config.js for local testing.";
    return;
  }

  const sb = window.supabase.createClient(cfg.supabaseUrl, cfg.supabaseAnonKey);
  let KENDRAS = [];
  let currentStaffForAssign = null;

  // ---------- auth ----------
  function showLogin() {
    $("#appView").classList.add("hidden");
    $("#appView").classList.remove("md:flex");
    $("#loginView").classList.remove("hidden");
    $("#loginView").classList.add("flex");
  }
  async function showApp(session) {
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
    const { data: ov, error: ovErr } = await sb.rpc("admin_overview");
    if (ovErr) {
      toast("Signed in, but this account is not registered as an admin.", "err");
      await sb.auth.signOut();
      return;
    }
    toast("Welcome back", "ok");
    showApp(data.session);
  });

  $("#logoutBtn").addEventListener("click", async () => {
    await sb.auth.signOut();
    toast("Signed out", "info");
    showLogin();
  });

  // ---------- navigation ----------
  const TITLES = { overview: "Overview", logs: "Attendance logs", staff: "Staff", kendra: "Kendras", bulk: "Bulk upload" };
  async function switchView(view) {
    $$(".nav-link").forEach((a) => a.classList.toggle("active", a.dataset.view === view));
    $$("[data-panel]").forEach((p) => p.classList.toggle("hidden", p.dataset.panel !== view));
    $("#viewTitle").textContent = TITLES[view] || "";
    if (view === "overview") await loadOverview();
    if (view === "logs") { await ensureKendras(); fillKendraFilter(); await loadLogs(); }
    if (view === "staff") { await ensureKendras(); await loadStaff(); }
    if (view === "kendra") await loadKendras();
  }
  $$(".nav-link").forEach((a) => a.addEventListener("click", () => switchView(a.dataset.view)));

  // ---------- data ----------
  async function ensureKendras() {
    const { data } = await sb.rpc("admin_list_kendras");
    KENDRAS = (data && data.rows) || [];
  }

  async function loadOverview() {
    veil(true);
    const { data, error } = await sb.rpc("admin_overview");
    veil(false);
    if (error) { toast(error.message, "err"); return; }
    const cards = [
      ["Present today", data.present_today, "text-green-600", "border-t-green-500"],
      ["Absent today", data.absent_today, "text-red-600", "border-t-red-500"],
      ["Late today", data.late_today, "text-amber-600", "border-t-amber-500"],
      ["Checked out", data.checked_out_today, "text-slate-600", "border-t-slate-400"],
      ["Total staff", data.total_staff, "text-blue-700", "border-t-blue-600"],
      ["Kendras", data.total_kendras, "text-blue-700", "border-t-blue-600"],
    ];
    $("#statCards").innerHTML = cards.map(([label, val, color, accent]) => `
      <div class="bg-white rounded-xl border border-slate-200 border-t-4 ${accent} p-4">
        <div class="text-xs text-slate-500">${label}</div>
        <div class="text-2xl font-bold ${color} mt-1">${val ?? 0}</div>
      </div>`).join("");
  }

  function fillKendraFilter() {
    const sel = $("#logKendra");
    const cur = sel.value;
    sel.innerHTML = `<option value="">All</option>` +
      KENDRAS.map((k) => `<option value="${k.id}">${esc(k.name)}</option>`).join("");
    sel.value = cur;
  }

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
        <th>Date</th><th>Staff</th><th>Mobile</th><th>Kendra</th>
        <th>Check-in</th><th>Check-out</th><th>Work time</th><th>Status</th><th>Present</th><th>Absent</th><th>Flags</th>
      </tr></thead>
      <tbody>${rows.map((r) => `
        <tr>
          <td>${esc(r.day)}</td>
          <td>${esc(r.staff_name)}</td>
          <td>${esc(r.mobile)}</td>
          <td>${esc(r.kendra || "—")}</td>
          <td>${esc(r.check_in_at || "—")}</td>
          <td>${esc(r.check_out_at || "—")}</td>
          <td>${fmtWork(r.work_minutes)}</td>
          <td>${statusBadge(r.status)}</td>
          <td class="text-green-600 font-semibold">${r.present_count ?? 0}</td>
          <td class="${(r.absent_count ?? 0) > 0 ? "text-red-600 font-semibold" : "text-slate-400"}">${r.absent_count ?? 0}</td>
          <td>${flagsBadges(r)}</td>
        </tr>`).join("")}</tbody>`;
  }
  function flagsBadges(r) {
    const out = [];
    if (r.check_out_reason === "left_area")
      out.push(`<span class="badge bg-red-600 text-white">LEFT AREA</span>`);
    if ((r.absent_streak_min ?? 0) >= 15)
      out.push(`<span class="badge bg-red-100 text-red-700">OUT ${r.absent_streak_min}m</span>`);
    if ((r.silence_min ?? 0) > 60)
      out.push(`<span class="badge bg-amber-100 text-amber-700">DARK ${r.silence_min}m</span>`);
    return out.length ? out.join(" ") : `<span class="text-slate-400">—</span>`;
  }
  function fmtWork(m) {
    if (m === null || m === undefined) return "—";
    return `${Math.floor(m / 60)}h ${m % 60}m`;
  }
  function statusBadge(s) {
    if (!s) return "—";
    const map = { present: "bg-green-100 text-green-700", late: "bg-amber-100 text-amber-700" };
    return `<span class="badge ${map[s] || "bg-slate-100 text-slate-600"}">${esc(s)}</span>`;
  }
  $("#logApply").addEventListener("click", loadLogs);

  async function loadStaff() {
    veil(true);
    const { data, error } = await sb.rpc("admin_list_staff");
    veil(false);
    if (error) { toast(error.message, "err"); return; }
    const rows = (data && data.rows) || [];
    if (!rows.length) { $("#staffTable").innerHTML = `<tr><td class="p-6 text-slate-500">No staff yet.</td></tr>`; return; }
    $("#staffTable").innerHTML = `
      <thead><tr><th>Name</th><th>Mobile</th><th>Role</th><th>Duty</th><th>Kendra(s)</th><th></th></tr></thead>
      <tbody>${rows.map((r) => `
        <tr>
          <td>${esc(r.full_name)}</td>
          <td>${esc(r.mobile_number)}</td>
          <td>${esc(r.role)}</td>
          <td>${esc(r.duty_type)}</td>
          <td class="text-slate-500">${esc(r.kendra_names || "—")}</td>
          <td>${r.role !== "admin"
            ? `<button data-assign='${esc(r.id)}' data-name='${esc(r.full_name)}' data-duty='${esc(r.duty_type)}'
                 class="text-blue-700 text-sm font-semibold hover:underline">Assign</button>`
            : ""}</td>
        </tr>`).join("")}</tbody>`;
    $$("#staffTable [data-assign]").forEach((b) =>
      b.addEventListener("click", () => openAssign(b.dataset.assign, b.dataset.name, b.dataset.duty)));
  }

  $("#enrollForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const btn = e.target.querySelector("button");
    busy(btn, true, "Adding…");
    const { data, error } = await sb.rpc("admin_enroll_staff", {
      p_full_name: $("#enName").value.trim(),
      p_mobile: $("#enMobile").value.trim(),
      p_role: "staff",
      p_duty_type: $("#enDuty").value,
    });
    busy(btn, false);
    if (error) { toast(error.message, "err"); return; }
    if (data && !data.ok) { toast(data.error || "Failed", "err"); return; }
    toast("Staff added — now assign a kendra.", "ok");
    e.target.reset();
    await loadStaff();
  });

  async function loadKendras() {
    veil(true);
    const { data, error } = await sb.rpc("admin_list_kendras");
    veil(false);
    if (error) { toast(error.message, "err"); return; }
    KENDRAS = (data && data.rows) || [];
    if (!KENDRAS.length) { $("#kendraTable").innerHTML = `<tr><td class="p-6 text-slate-500">No kendras yet.</td></tr>`; return; }
    $("#kendraTable").innerHTML = `
      <thead><tr><th>Name</th><th>Lat</th><th>Long</th><th>Radius</th><th>Window</th><th>Day ends</th><th>Verify</th><th></th></tr></thead>
      <tbody>${KENDRAS.map((k) => `
        <tr>
          <td>${esc(k.name)}</td><td>${k.latitude}</td><td>${k.longitude}</td>
          <td>${k.radius_m} m</td><td>${esc(k.check_in_start)}–${esc(k.check_in_end)}</td>
          <td>${esc(k.day_end)}</td><td>every ${k.verify_interval_min} min</td>
          <td><button data-kedit="${k.id}" class="text-blue-700 text-sm font-semibold hover:underline">Edit</button></td>
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
      p_check_in_start: $("#kInStart").value || "08:00",
      p_day_end: $("#kDayEnd").value || "18:00",
      p_verify_interval_min: parseInt($("#kVerify").value || "30", 10),
    });
    busy(btn, false);
    if (error) { toast(error.message, "err"); return; }
    if (data && !data.ok) { toast(data.error || "Failed", "err"); return; }
    toast("Kendra created", "ok");
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
    $("#keInStart").value = k.check_in_start;
    $("#keDayEnd").value = k.day_end;
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
      p_check_in_start: $("#keInStart").value || null,
      p_day_end: $("#keDayEnd").value || null,
      p_verify_interval_min: parseInt($("#keVerify").value, 10),
    });
    busy(btn, false);
    if (error) { toast(error.message, "err"); return; }
    if (data && !data.ok) { toast(data.error || "Failed", "err"); return; }
    toast("Kendra updated. Staff phones pick up the new location when they next open the app.", "ok");
    closeKendraEdit();
    await loadKendras();
  });

  // ---------- assign modal ----------
  async function openAssign(staffId, name, duty) {
    currentStaffForAssign = { id: staffId, duty };
    $("#assignName").textContent = name;
    $("#assignHint").textContent = duty === "fixed"
      ? "Fixed duty: selecting a kendra replaces any previous one."
      : "Multi duty: tick every kendra this person may mark attendance from.";
    await ensureKendras();
    const { data } = await sb.rpc("admin_staff_kendras", { p_staff_id: staffId });
    const assigned = new Set((data && data.kendra_ids) || []);
    $("#assignList").innerHTML = KENDRAS.length
      ? KENDRAS.map((k) => `
        <label class="flex items-center gap-3 p-2 rounded-lg hover:bg-slate-50 cursor-pointer">
          <input type="checkbox" data-kid="${k.id}" ${assigned.has(k.id) ? "checked" : ""} class="w-4 h-4"/>
          <span class="text-sm">${esc(k.name)}</span>
        </label>`).join("")
      : `<p class="text-sm text-slate-500">No kendras exist yet.</p>`;
    $$("#assignList input[type=checkbox]").forEach((cb) =>
      cb.addEventListener("change", () => toggleAssign(cb.dataset.kid, cb.checked, duty)));
    const m = $("#assignModal");
    m.classList.remove("hidden"); m.classList.add("flex");
  }
  async function toggleAssign(kendraId, allowed, duty) {
    const { error } = await sb.rpc("admin_assign_kendra", {
      p_staff_id: currentStaffForAssign.id, p_kendra_id: kendraId, p_allowed: allowed,
    });
    if (error) { toast(error.message, "err"); return; }
    toast(allowed ? "Kendra assigned" : "Kendra removed", "ok");
    if (duty === "fixed" && allowed) {
      // reflect server-side replace
      const { data } = await sb.rpc("admin_staff_kendras", { p_staff_id: currentStaffForAssign.id });
      const assigned = new Set((data && data.kendra_ids) || []);
      $$("#assignList input[type=checkbox]").forEach((cb) => { cb.checked = assigned.has(cb.dataset.kid); });
    }
    await loadStaff();
  }
  $("#assignClose").addEventListener("click", () => {
    const m = $("#assignModal"); m.classList.add("hidden"); m.classList.remove("flex");
  });

  // ---------- bulk excel ----------
  const HEADERS = ["Full name", "Mobile number", "Role", "Duty type", "Kendra name(s)"];
  $("#tplBtn").addEventListener("click", () => {
    const ws = XLSX.utils.aoa_to_sheet([
      HEADERS,
      ["Ram Kumar", "9812345670", "staff", "fixed", "Kendra Raipur-1"],
      ["Sita Verma", "9876543210", "staff", "multi", "Kendra Raipur-1, Kendra Dhamtari-2"],
    ]);
    ws["!cols"] = [{ wch: 20 }, { wch: 15 }, { wch: 10 }, { wch: 12 }, { wch: 40 }];
    const wb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(wb, ws, "Staff");
    XLSX.writeFile(wb, "dks-staff-template.xlsx");
  });

  function normKey(k) { return String(k).toLowerCase().replace(/[^a-z]/g, ""); }
  function mapRow(raw) {
    const out = { full_name: "", mobile_number: "", role: "staff", duty_type: "fixed", kendra_names: "" };
    for (const [k, v] of Object.entries(raw)) {
      const key = normKey(k);
      const val = String(v ?? "").trim();
      if (["fullname", "name"].includes(key)) out.full_name = val;
      else if (["mobilenumber", "mobile", "phone", "phonenumber"].includes(key)) out.mobile_number = val.replace(/\D/g, "");
      else if (key === "role") out.role = (val || "staff").toLowerCase();
      else if (["dutytype", "duty"].includes(key)) out.duty_type = (val || "fixed").toLowerCase();
      else if (["kendranames", "kendraname", "kendra", "kendras"].includes(key)) out.kendra_names = val;
    }
    return out;
  }

  let BULK_ROWS = [];
  $("#xlsxFile").addEventListener("change", async (e) => {
    const file = e.target.files[0];
    if (!file) return;
    const buf = await file.arrayBuffer();
    const wb = XLSX.read(buf, { type: "array" });
    const sheet = wb.Sheets[wb.SheetNames[0]];
    const json = XLSX.utils.sheet_to_json(sheet, { defval: "" });
    BULK_ROWS = json.map(mapRow).filter((r) => r.full_name || r.mobile_number);
    $("#bulkCount").textContent = BULK_ROWS.length;
    $("#bulkPreviewWrap").classList.toggle("hidden", BULK_ROWS.length === 0);
    $("#bulkResultWrap").classList.add("hidden");
    $("#bulkPreview").innerHTML = `
      <thead><tr><th>Name</th><th>Mobile</th><th>Role</th><th>Duty</th><th>Kendra(s)</th></tr></thead>
      <tbody>${BULK_ROWS.map((r) => `
        <tr><td>${esc(r.full_name)}</td><td>${esc(r.mobile_number)}</td><td>${esc(r.role)}</td>
        <td>${esc(r.duty_type)}</td><td class="text-slate-500">${esc(r.kendra_names)}</td></tr>`).join("")}</tbody>`;
    if (!BULK_ROWS.length) toast("No rows found. Check the header row.", "err");
  });

  $("#bulkSubmit").addEventListener("click", async () => {
    if (!BULK_ROWS.length) { toast("Nothing to upload.", "err"); return; }
    const btn = $("#bulkSubmit");
    busy(btn, true, "Uploading…");
    const { data, error } = await sb.rpc("admin_bulk_enroll", { p_rows: BULK_ROWS });
    busy(btn, false);
    if (error) { toast(error.message, "err"); return; }
    const results = (data && data.results) || [];
    const okCount = results.filter((r) => r.ok).length;
    $("#bulkResultWrap").classList.remove("hidden");
    $("#bulkResult").innerHTML = `
      <thead><tr><th>Name</th><th>Mobile</th><th>Result</th></tr></thead>
      <tbody>${results.map((r) => `
        <tr>
          <td>${esc(r.name)}</td><td>${esc(r.mobile)}</td>
          <td>${r.ok
            ? `<span class="badge bg-green-100 text-green-700">Enrolled</span>`
            : `<span class="badge bg-red-100 text-red-700">${esc(r.error || "Failed")}</span>`}</td>
        </tr>`).join("")}</tbody>`;
    toast(`${okCount} of ${results.length} enrolled.`, okCount ? "ok" : "err");
    BULK_ROWS = []; $("#xlsxFile").value = "";
    $("#bulkPreviewWrap").classList.add("hidden");
  });

  // ---------- session bootstrap ----------
  sb.auth.getSession().then(({ data }) => {
    if (data.session) showApp(data.session); else showLogin();
  });
  sb.auth.onAuthStateChange((_evt, session) => {
    if (!session) showLogin();
  });
})();
