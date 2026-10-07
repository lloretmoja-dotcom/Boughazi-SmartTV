/* Boughazi-TV — Panel de administración (proyecto nuevo)
   Todo el código de esta página en un único archivo, comentado en
   español para que sea fácil de seguir. */

const el = (id) => document.getElementById(id);

const state = {
  channels: [],
  selectedIds: new Set(),
  statsTimer: null,
  importItems: [],
};

/* ------------------------------------------------------------ */
/* Sesión / login                                                */
/* ------------------------------------------------------------ */

async function init() {
  const { data } = await supabaseClient.auth.getSession();
  if (data.session) {
    await tryEnterDashboard();
  } else {
    showLogin();
  }
}

function showLogin() {
  el("login-screen").classList.remove("hidden");
  el("dashboard").classList.add("hidden");
}

async function tryEnterDashboard() {
  // Comprobamos en el servidor si esta cuenta es administradora.
  const { data: isAdmin, error } = await supabaseClient.rpc("bt_is_admin");
  if (error || !isAdmin) {
    el("login-error").textContent = "Esta cuenta no tiene permiso de administrador.";
    el("login-error").classList.remove("hidden");
    await supabaseClient.auth.signOut();
    showLogin();
    return;
  }
  el("login-screen").classList.add("hidden");
  el("dashboard").classList.remove("hidden");
  await loadChannels();
  await refreshStats();
  state.statsTimer = setInterval(refreshStats, 15000);
}

el("login-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  el("login-error").classList.add("hidden");
  const email = el("login-email").value.trim();
  const password = el("login-password").value;
  const { error } = await supabaseClient.auth.signInWithPassword({ email, password });
  if (error) {
    el("login-error").textContent = "Correo o contraseña incorrectos.";
    el("login-error").classList.remove("hidden");
    return;
  }
  await tryEnterDashboard();
});

el("logout-btn").addEventListener("click", async () => {
  clearInterval(state.statsTimer);
  await supabaseClient.auth.signOut();
  showLogin();
});

/* ------------------------------------------------------------ */
/* Pestañas                                                       */
/* ------------------------------------------------------------ */

document.querySelectorAll(".tab").forEach((btn) => {
  btn.addEventListener("click", () => {
    document.querySelectorAll(".tab").forEach((b) => b.classList.remove("active"));
    document.querySelectorAll(".tab-panel").forEach((p) => p.classList.add("hidden"));
    btn.classList.add("active");
    el("tab-" + btn.dataset.tab).classList.remove("hidden");
    if (btn.dataset.tab === "codes") loadCodes();
  });
});

/* ------------------------------------------------------------ */
/* Estadísticas                                                   */
/* ------------------------------------------------------------ */

async function refreshStats() {
  const { count: registered } = await supabaseClient
    .from("bt_viewers")
    .select("id", { count: "exact", head: true });

  const cutoff = new Date(Date.now() - 60 * 1000).toISOString();
  const { count: online } = await supabaseClient
    .from("bt_presence")
    .select("viewer_id", { count: "exact", head: true })
    .gte("last_ping", cutoff);

  const { count: activeChannels } = await supabaseClient
    .from("bt_channels")
    .select("id", { count: "exact", head: true })
    .eq("is_broken", false);

  el("stat-registered").textContent = registered ?? "—";
  el("stat-online").textContent = online ?? "—";
  el("stat-channels").textContent = activeChannels ?? "—";

  // Prueba real de que la comprobación automática de canales se ha
  // ejecutado de verdad: se busca la fecha más reciente guardada en
  // "last_checked_at" (el sistema automático la pone en TODOS los
  // canales cada vez que se ejecuta, hayan cambiado de estado o no).
  // Así no hay que fiarse solo de que todo salga en verde.
  const { data: lastCheckRows } = await supabaseClient
    .from("bt_channels")
    .select("last_checked_at")
    .not("last_checked_at", "is", null)
    .order("last_checked_at", { ascending: false })
    .limit(1);

  el("stat-last-check").textContent = formatLastCheck(
    lastCheckRows && lastCheckRows[0] ? lastCheckRows[0].last_checked_at : null
  );
}

function formatLastCheck(iso) {
  if (!iso) return "Todavía no se ha comprobado ningún canal";
  const diffMin = Math.round((Date.now() - new Date(iso).getTime()) / 60000);
  if (diffMin < 1) return "Hace un momento";
  if (diffMin < 60) return `Hace ${diffMin} minuto${diffMin === 1 ? "" : "s"}`;
  const diffH = Math.round(diffMin / 60);
  if (diffH < 24) return `Hace ${diffH} hora${diffH === 1 ? "" : "s"}`;
  const diffD = Math.round(diffH / 24);
  return `Hace ${diffD} día${diffD === 1 ? "" : "s"}`;
}

/* ------------------------------------------------------------ */
/* Canales                                                        */
/* ------------------------------------------------------------ */

async function loadChannels() {
  el("channels-status").textContent = "Cargando canales…";

  // Supabase solo entrega 1000 filas como máximo por cada petición.
  // Como ya hemos pasado de 1000 canales, pedimos la lista por partes
  // (de 1000 en 1000) hasta traerlos todos, en vez de una sola vez.
  const pageSize = 1000;
  let all = [];
  let from = 0;
  while (true) {
    const { data, error } = await supabaseClient
      .from("bt_channels")
      .select("*")
      .order("channel_number", { ascending: true, nullsFirst: false })
      .range(from, from + pageSize - 1);

    if (error) {
      el("channels-status").textContent = "Error al cargar: " + error.message;
      return;
    }
    all = all.concat(data || []);
    if (!data || data.length < pageSize) break;
    from += pageSize;
  }

  state.channels = all;
  state.selectedIds.clear();
  updateBulkBar();
  renderChannels();
  el("channels-status").textContent = state.channels.length
    ? ""
    : "Todavía no has añadido ningún canal.";
}

function renderChannels() {
  el("channels-tbody").innerHTML = state.channels
    .map((c) => {
      const statusHtml = c.is_broken
        ? '<span class="status-broken">⚠ Caído</span>'
        : '<span class="status-ok">● OK</span>';
      return `
        <tr>
          <td><input type="checkbox" class="row-check" data-id="${escapeHtml(c.id)}" ${
        state.selectedIds.has(c.id) ? "checked" : ""
      } /></td>
          <td>${escapeHtml(c.channel_number ?? "—")}</td>
          <td class="name-cell" dir="auto">${escapeHtml(c.name)}</td>
          <td class="cat-cell" dir="auto">${escapeHtml(c.category || "")}</td>
          <td>${statusHtml}</td>
          <td>${
        safeHttpUrl(c.stream_url)
          ? `<a class="link-icon" href="${escapeHtml(c.stream_url)}" target="_blank" rel="noopener noreferrer">ver enlace</a>`
          : '<span class="status-broken">enlace no válido</span>'
      }</td>
          <td><button class="btn danger" data-delete="${escapeHtml(c.id)}">Borrar</button></td>
        </tr>`;
    })
    .join("");

  document.querySelectorAll(".row-check").forEach((cb) => {
    cb.addEventListener("change", () => {
      if (cb.checked) state.selectedIds.add(cb.dataset.id);
      else state.selectedIds.delete(cb.dataset.id);
      updateBulkBar();
    });
  });
  document.querySelectorAll("[data-delete]").forEach((btn) => {
    btn.addEventListener("click", () => deleteChannels([btn.dataset.delete]));
  });
}

/* Solo se aceptan enlaces que empiecen por http:// o https://. Las listas
   M3U suelen venir de terceros: un enlace del tipo "javascript:..."
   podría ejecutar código en este panel con tu sesión de administrador
   (y desde aquí se puede borrar o cambiar todo). */
function safeHttpUrl(str) {
  try {
    const u = new URL(String(str || "").trim());
    return u.protocol === "http:" || u.protocol === "https:";
  } catch (_err) {
    return false;
  }
}

function escapeHtml(str) {
  return String(str).replace(/[&<>"']/g, (ch) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  }[ch]));
}

el("select-all").addEventListener("change", (e) => {
  if (e.target.checked) state.channels.forEach((c) => state.selectedIds.add(c.id));
  else state.selectedIds.clear();
  renderChannels();
  updateBulkBar();
});

function updateBulkBar() {
  const n = state.selectedIds.size;
  el("bulk-bar").classList.toggle("hidden", n === 0);
  el("bulk-count").textContent = `${n} seleccionado${n === 1 ? "" : "s"}`;
}

el("bulk-clear-btn").addEventListener("click", () => {
  state.selectedIds.clear();
  renderChannels();
  updateBulkBar();
});

el("bulk-delete-btn").addEventListener("click", async () => {
  const ids = Array.from(state.selectedIds);
  if (!ids.length) return;
  if (!confirm(`¿Borrar ${ids.length} canal(es) seleccionados? No se puede deshacer.`)) return;
  await deleteChannels(ids);
});

async function deleteChannels(ids) {
  // Si son muchos canales (por ejemplo, todos de golpe con "Seleccionar
  // todos"), borrarlos en una sola petición puede fallar porque el
  // enlace se hace demasiado largo. Los borramos en tandas de 200 en
  // 200, mostrando el progreso, hasta terminar con todos.
  const chunkSize = 200;
  const btn = el("bulk-delete-btn");
  const originalLabel = btn.textContent;
  btn.disabled = true;

  for (let i = 0; i < ids.length; i += chunkSize) {
    const chunk = ids.slice(i, i + chunkSize);
    btn.textContent = `Borrando… ${Math.min(i + chunkSize, ids.length)}/${ids.length}`;
    const { error } = await supabaseClient.from("bt_channels").delete().in("id", chunk);
    if (error) {
      alert("No se pudo borrar: " + error.message);
      btn.disabled = false;
      btn.textContent = originalLabel;
      await loadChannels();
      await refreshStats();
      return;
    }
  }

  btn.disabled = false;
  btn.textContent = originalLabel;
  ids.forEach((id) => state.selectedIds.delete(id));
  await loadChannels();
  await refreshStats();
}

/* ---- Añadir canal ---- */
el("new-channel-btn").addEventListener("click", () => {
  el("channel-form").reset();
  el("channel-form-error").classList.add("hidden");
  el("channel-modal").classList.remove("hidden");
});

document.querySelectorAll("[data-close]").forEach((btn) => {
  btn.addEventListener("click", () => el(btn.dataset.close).classList.add("hidden"));
});

el("channel-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const payload = {
    channel_number: el("cf-number").value.trim() ? Number(el("cf-number").value.trim()) : null,
    name: el("cf-name").value.trim(),
    category: el("cf-category").value.trim() || null,
    logo_url: el("cf-logo").value.trim() || null,
    stream_url: el("cf-src").value.trim(),
  };
  if (!safeHttpUrl(payload.stream_url)) {
    el("channel-form-error").textContent = "El enlace del canal tiene que empezar por http:// o https://";
    el("channel-form-error").classList.remove("hidden");
    return;
  }
  if (payload.logo_url && !safeHttpUrl(payload.logo_url)) payload.logo_url = null;
  const { error } = await supabaseClient.from("bt_channels").insert(payload);
  if (error) {
    el("channel-form-error").textContent = error.message;
    el("channel-form-error").classList.remove("hidden");
    return;
  }
  el("channel-modal").classList.add("hidden");
  await loadChannels();
  await refreshStats();
});

/* ---- Verificar canales caídos ----
   Comprobación "best effort": muchos servidores de streaming
   bloquean estas peticiones desde el navegador (CORS), así que
   algunos canales que SÍ funcionan en la app pueden aparecer como
   "no comprobado". Es una ayuda, no una garantía al 100%. */
el("check-channels-btn").addEventListener("click", async () => {
  el("channels-status").textContent = "Comprobando canales, puede tardar un poco…";
  for (const c of state.channels) {
    let broken = false;
    try {
      const res = await fetch(c.stream_url, { method: "GET", mode: "cors" });
      broken = !res.ok;
    } catch (_err) {
      // Si el navegador bloquea la petición (CORS) no podemos saberlo
      // con seguridad, así que no lo marcamos como caído por eso solo.
      broken = false;
    }
    if (broken !== c.is_broken) {
      await supabaseClient
        .from("bt_channels")
        // "last_checked_at" guarda la última vez que el canal FUNCIONÓ (el
        // script nocturno lo usa para saber cuántos días lleva caído), así
        // que solo se pone al día cuando el canal responde bien.
        .update(broken ? { is_broken: true } : { is_broken: false, last_checked_at: new Date().toISOString() })
        .eq("id", c.id);
    }
  }
  await loadChannels();
});

/* ---- Importar lista de canales (M3U / M3U8 / texto simple) ----
   Así no hay que añadir los canales uno a uno con un enlace: se
   sube el archivo entero y se rellenan todos de golpe. */

el("import-channels-btn").addEventListener("click", () => {
  el("import-file-input").value = "";
  el("import-category-override").value = "";
  el("import-status").textContent = "";
  el("import-preview-wrap").classList.add("hidden");
  el("import-confirm-btn").classList.add("hidden");
  el("import-error").classList.add("hidden");
  state.importItems = [];
  el("import-modal").classList.remove("hidden");
});

function parseChannelList(text) {
  const lines = text.split(/\r?\n/);
  const items = [];
  let pending = null; // datos del #EXTINF que estamos esperando emparejar con su enlace

  for (const raw of lines) {
    const line = raw.trim();
    if (!line) continue;

    if (line.toUpperCase().startsWith("#EXTINF")) {
      const commaIdx = line.indexOf(",");
      const attrsPart = commaIdx >= 0 ? line.slice(0, commaIdx) : line;
      const namePart = commaIdx >= 0 ? line.slice(commaIdx + 1).trim() : "";
      const logoMatch = attrsPart.match(/tvg-logo="([^"]*)"/i);
      const groupMatch = attrsPart.match(/group-title="([^"]*)"/i);
      pending = {
        name: namePart || `Canal ${items.length + 1}`,
        category: groupMatch ? groupMatch[1] : "",
        logoUrl: logoMatch ? logoMatch[1] : "",
      };
      continue;
    }

    if (line.startsWith("#")) continue; // otras etiquetas del M3U, se ignoran

    // Cualquier otra línea no vacía la tratamos como el enlace del canal.
    if (pending) {
      items.push({ ...pending, streamUrl: line });
      pending = null;
    } else {
      // Archivo de texto simple: un enlace por línea, sin #EXTINF.
      items.push({ name: `Canal ${items.length + 1}`, category: "", logoUrl: "", streamUrl: line });
    }
  }
  return items;
}

el("import-file-input").addEventListener("change", async (e) => {
  const file = e.target.files[0];
  if (!file) return;
  const text = await file.text();
  const parsed = parseChannelList(text).filter((it) => it.streamUrl);
  // Se descartan los enlaces que no son http:// ni https:// (ver safeHttpUrl).
  const items = parsed.filter((it) => safeHttpUrl(it.streamUrl));
  const skipped = parsed.length - items.length;

  if (!items.length) {
    el("import-status").textContent = "No se ha encontrado ningún canal en ese archivo. Comprueba que sea un M3U válido.";
    el("import-preview-wrap").classList.add("hidden");
    el("import-confirm-btn").classList.add("hidden");
    return;
  }

  state.importItems = items.map((it, i) => ({ ...it, id: i, selected: true }));
  el("import-status").textContent =
    `${items.length} canal(es) encontrados. Quita el visto de los que no quieras subir y toca "Importar".` +
    (skipped ? ` (Se han descartado ${skipped} con un enlace no válido.)` : "");
  renderImportPreview();
  el("import-preview-wrap").classList.remove("hidden");
  el("import-select-all").checked = true;
  el("import-confirm-btn").classList.remove("hidden");
  updateImportConfirmLabel();
});

function renderImportPreview() {
  const override = el("import-category-override").value.trim();
  el("import-preview-tbody").innerHTML = state.importItems
    .map(
      (it) => `
        <tr>
          <td><input type="checkbox" class="import-row-check" data-id="${escapeHtml(it.id)}" ${it.selected ? "checked" : ""} /></td>
          <td>${escapeHtml(it.name)}</td>
          <td>${escapeHtml(override || it.category || "")}</td>
        </tr>`
    )
    .join("");

  document.querySelectorAll(".import-row-check").forEach((cb) => {
    cb.addEventListener("change", () => {
      const item = state.importItems.find((it) => String(it.id) === cb.dataset.id);
      if (item) item.selected = cb.checked;
      updateImportConfirmLabel();
    });
  });
}

function updateImportConfirmLabel() {
  const n = state.importItems.filter((it) => it.selected).length;
  el("import-confirm-btn").textContent = `Importar ${n} canal${n === 1 ? "" : "es"}`;
}

el("import-select-all").addEventListener("change", (e) => {
  state.importItems.forEach((it) => (it.selected = e.target.checked));
  renderImportPreview();
  updateImportConfirmLabel();
});

el("import-category-override").addEventListener("input", () => {
  if (state.importItems.length) renderImportPreview();
});

el("import-confirm-btn").addEventListener("click", async () => {
  const chosen = state.importItems.filter((it) => it.selected);
  if (!chosen.length) return;

  const override = el("import-category-override").value.trim();
  let nextNumber = state.channels.reduce((max, c) => Math.max(max, c.channel_number || 0), 0) + 1;
  const rows = chosen.map((it) => ({
    channel_number: nextNumber++,
    name: it.name,
    category: override || it.category || null,
    logo_url: safeHttpUrl(it.logoUrl) ? it.logoUrl : null,
    stream_url: it.streamUrl,
  }));

  el("import-confirm-btn").disabled = true;
  el("import-confirm-btn").textContent = "Importando…";
  el("import-error").classList.add("hidden");

  const { error } = await supabaseClient.from("bt_channels").insert(rows);

  el("import-confirm-btn").disabled = false;
  if (error) {
    el("import-error").textContent = "No se pudo importar: " + error.message;
    el("import-error").classList.remove("hidden");
    updateImportConfirmLabel();
    return;
  }

  el("import-modal").classList.add("hidden");
  await loadChannels();
  await refreshStats();
});

/* ------------------------------------------------------------ */
/* Códigos de acceso                                               */
/* ------------------------------------------------------------ */

function generateCode() {
  const chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // sin caracteres confusos
  let out = "";
  for (let i = 0; i < 8; i++) out += chars[Math.floor(Math.random() * chars.length)];
  return out;
}

async function loadCodes() {
  const { data, error } = await supabaseClient
    .from("bt_access_codes")
    .select("*")
    .order("created_at", { ascending: false });
  if (error) return;
  el("codes-tbody").innerHTML = data
    .map((code) => {
      const statusHtml = code.used_by_email
        ? '<span class="status-broken">Usado</span>'
        : '<span class="status-ok">Libre</span>';
      return `
        <tr>
          <td>${escapeHtml(code.code)}</td>
          <td>${escapeHtml(code.label || "")}</td>
          <td>${statusHtml}</td>
          <td>${escapeHtml(code.used_by_email || "—")}</td>
          <td><button class="btn danger" data-del-code="${escapeHtml(code.id)}">Borrar</button></td>
        </tr>`;
    })
    .join("");

  document.querySelectorAll("[data-del-code]").forEach((btn) => {
    btn.addEventListener("click", async () => {
      if (!confirm("¿Borrar este código?")) return;
      await supabaseClient.from("bt_access_codes").delete().eq("id", btn.dataset.delCode);
      loadCodes();
    });
  });
}

let pendingCodeId = null;

el("new-code-btn").addEventListener("click", async () => {
  const code = generateCode();
  const { data, error } = await supabaseClient
    .from("bt_access_codes")
    .insert({ code })
    .select()
    .single();
  if (error) {
    alert("No se pudo generar el código: " + error.message);
    return;
  }
  pendingCodeId = data.id;
  el("new-code-value").textContent = data.code;
  el("code-label-input").value = "";
  el("code-modal").classList.remove("hidden");
});

el("code-label-save").addEventListener("click", async () => {
  const label = el("code-label-input").value.trim();
  if (label && pendingCodeId) {
    await supabaseClient.from("bt_access_codes").update({ label }).eq("id", pendingCodeId);
  }
  el("code-modal").classList.add("hidden");
  loadCodes();
});

init();
