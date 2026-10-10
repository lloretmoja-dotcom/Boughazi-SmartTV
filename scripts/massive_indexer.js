/*
 * Robot diario de canales (IPTV Massive Indexer)
 * ----------------------------------------------
 * Cada día, desde GitHub Actions (ver .github/workflows/daily_indexer.yml):
 *
 *   1. Descarga los canales de listas públicas de canales en abierto:
 *      iptv-org, TDTChannels, Free-TV y las listas extra que pongas en
 *      scripts/indexer-config.json.
 *   2. Los agrupa por país y por nombre ("La 1", "La 1 HD" y "La 1 (1080p)"
 *      son el mismo canal) y se queda con el enlace de más calidad que
 *      funcione de verdad: cada enlace se prueba como lo haría la tele.
 *   3. Lo compara con lo que ya hay en Supabase:
 *        - canal nuevo           → se añade con su país, bandera y logo
 *        - canal que ya tienes   → no se toca
 *        - canal tuyo caído      → si hay otro enlace que funciona, se
 *                                  cambia por ese y vuelve a salir en la tele
 *   4. Manda un informe por Gmail (y lo deja también en la pestaña Actions).
 *
 * Los canales que dejan de funcionar los sigue marcando y borrando el
 * revisor nocturno de siempre (scripts/check_channels.py); este robot no
 * borra nada.
 */

const fs = require("fs");
const path = require("path");

const SUPABASE_URL = (process.env.SUPABASE_URL || "https://oansihwqjcfjackfhgbd.supabase.co").replace(/\/+$/, "");
const SUPABASE_KEY = (process.env.SUPABASE_KEY || process.env.SUPABASE_SERVICE_ROLE_KEY || "").trim();
const DRY_RUN = process.env.DRY_RUN === "1"; // prueba sin escribir nada en Supabase

const CONFIG = (() => {
  const file = path.join(__dirname, "indexer-config.json");
  const defaults = {
    countries: [],
    extra_m3u_sources: [],
    exclude_nsfw: true,
    max_new_per_run: 0,
    check_timeout_seconds: 5,
    check_concurrency: 64,
  };
  try {
    return { ...defaults, ...JSON.parse(fs.readFileSync(file, "utf8")) };
  } catch (_err) {
    return defaults;
  }
})();

// La tele reproduce con ExoPlayer: se prueba cada enlace con un "User-Agent"
// parecido, así un canal que solo funciona en navegadores no cuenta como bueno.
const TV_USER_AGENT = "BoughaziTV/1.0 (Linux; Android 11) ExoPlayerLib/1.3.1";

const SOURCES = {
  iptvOrgChannels: "https://iptv-org.github.io/api/channels.json",
  iptvOrgStreams: "https://iptv-org.github.io/api/streams.json",
  iptvOrgLogos: "https://iptv-org.github.io/api/logos.json",
  tdtChannels: "https://www.tdtchannels.com/lists/tv.json",
  freeTv: "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8",
};

/* ------------------------------------------------------------------ */
/* Países, banderas y nombres                                           */
/* ------------------------------------------------------------------ */

function normalizeText(str) {
  return String(str || "")
    .toLowerCase()
    .normalize("NFD")
    .replace(/[̀-ͯ]/g, "")
    .trim();
}

const regionEs = new Intl.DisplayNames(["es"], { type: "region" });
const regionEn = new Intl.DisplayNames(["en"], { type: "region" });

function spanishCountryName(code) {
  try {
    const name = regionEs.of(code);
    return name && name !== code ? name : null;
  } catch (_err) {
    return null;
  }
}

function flagFromCode(code) {
  if (!/^[A-Z]{2}$/.test(code || "")) return "📺";
  return String.fromCodePoint(...[...code].map((ch) => 0x1f1e6 + ch.charCodeAt(0) - 65));
}

const COUNTRY_ALIASES = {
  uk: "GB", "united kingdom": "GB", england: "GB", usa: "US", "united states": "US",
  "south korea": "KR", korea: "KR", "north korea": "KP", russia: "RU",
  "czech republic": "CZ", holland: "NL", netherlands: "NL", "the netherlands": "NL",
  uae: "AE", "united arab emirates": "AE", "bosnia and herzegovina": "BA",
  "ivory coast": "CI", "vatican city": "VA", kosovo: "XK", "north macedonia": "MK",
  macedonia: "MK", "dominican republic": "DO", taiwan: "TW", "hong kong": "HK",
  palestine: "PS", vietnam: "VN", "viet nam": "VN", iran: "IR", syria: "SY",
  bolivia: "BO", venezuela: "VE", tanzania: "TZ", moldova: "MD", laos: "LA",
  "cape verde": "CV", eswatini: "SZ", turkey: "TR", turkiye: "TR",
};

const codeByName = (() => {
  const map = new Map();
  const letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
  for (const a of letters) {
    for (const b of letters) {
      const code = a + b;
      const es = spanishCountryName(code);
      if (es) map.set(normalizeText(es), code);
      try {
        const en = regionEn.of(code);
        if (en && en !== code) map.set(normalizeText(en), code);
      } catch (_err) {
        /* código desconocido */
      }
    }
  }
  for (const [alias, code] of Object.entries(COUNTRY_ALIASES)) map.set(alias, code);
  return map;
})();

function countryCodeFromName(name) {
  return codeByName.get(normalizeText(name).replace(/^the\s+/, "")) || null;
}

/* Igual que en el panel (channelNameKey en app.js): el mismo canal aunque
   venga escrito un poco distinto. Los nombres genéricos no cuentan. */
function channelNameKey(name, category) {
  const clean = normalizeText(name)
    .replace(/\([^)]*\)|\[[^\]]*\]/g, " ")
    .replace(/\b(uhd|fhd|hd|sd|4k|tv hd|backup)\b\s*$/g, " ")
    .replace(/[^a-z0-9؀-ۿ]+/g, " ")
    .trim();
  if (!clean || /^canal \d+$/.test(clean)) return null;
  const cat = normalizeText(category && String(category).trim() ? category : "Sin categoría");
  return `${cat}|${clean}`;
}

/* Nombre para la tele, sin "(720p)", "[Geo-blocked]" ni "(Backup)". */
function displayName(name) {
  return String(name || "")
    .replace(/\([^)]*\)|\[[^\]]*\]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function qualityScore(...texts) {
  let best = 0;
  for (const t of texts) {
    const s = String(t || "").toLowerCase();
    const m = s.match(/(\d{3,4})p/);
    if (m) best = Math.max(best, Number(m[1]));
    else if (/\b(4k|uhd)\b/.test(s)) best = Math.max(best, 2160);
    else if (/\bfhd\b/.test(s)) best = Math.max(best, 1080);
    else if (/\bhd\b/.test(s)) best = Math.max(best, 720);
  }
  return best;
}

function isHttpUrl(str) {
  try {
    const u = new URL(String(str || "").trim());
    return u.protocol === "http:" || u.protocol === "https:";
  } catch (_err) {
    return false;
  }
}

/* ------------------------------------------------------------------ */
/* Fuentes                                                              */
/* ------------------------------------------------------------------ */

async function download(url, { json = false, timeoutMs = 120000 } = {}) {
  const controller = new AbortController();
  const t = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(url, { signal: controller.signal, headers: { "User-Agent": "BoughaziIndexer" } });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return json ? await res.json() : await res.text();
  } finally {
    clearTimeout(t);
  }
}

function parseM3U(text) {
  const items = [];
  let pending = null;
  for (const raw of String(text).replace(/^﻿/, "").split(/\r?\n/)) {
    const line = raw.trim();
    if (!line) continue;
    if (line.toUpperCase().startsWith("#EXTINF")) {
      let inQuotes = false;
      let comma = -1;
      for (let i = 0; i < line.length; i++) {
        if (line[i] === '"') inQuotes = !inQuotes;
        else if (line[i] === "," && !inQuotes) { comma = i; break; }
      }
      const attrs = comma >= 0 ? line.slice(0, comma) : line;
      pending = {
        name: comma >= 0 ? line.slice(comma + 1).trim() : "",
        group: (attrs.match(/group-title="([^"]*)"/i) || [])[1] || "",
        logo: (attrs.match(/tvg-logo="([^"]*)"/i) || [])[1] || "",
        country: (attrs.match(/tvg-country="([^"]*)"/i) || [])[1] || "",
      };
      continue;
    }
    if (line.startsWith("#")) continue;
    if (pending) {
      items.push({ ...pending, url: line });
      pending = null;
    }
  }
  return items;
}

/* Cada fuente devuelve candidatos: { code, name, logo, url, quality, source } */

async function fromIptvOrg() {
  const [channels, streams] = await Promise.all([
    download(SOURCES.iptvOrgChannels, { json: true }),
    download(SOURCES.iptvOrgStreams, { json: true }),
  ]);
  let logos = [];
  try {
    logos = await download(SOURCES.iptvOrgLogos, { json: true });
  } catch (_err) {
    logos = []; // versiones antiguas de la API traían el logo dentro del canal
  }
  const bestLogo = new Map();
  for (const l of Array.isArray(logos) ? logos : []) {
    if (!l || !l.channel || !isHttpUrl(l.url) || l.feed) continue;
    const prev = bestLogo.get(l.channel);
    if (!prev || (Number(l.width) || 0) > (Number(prev.width) || 0)) bestLogo.set(l.channel, l);
  }
  const byId = new Map(channels.map((c) => [c.id, c]));
  const out = [];
  for (const s of streams) {
    const ch = s && byId.get(s.channel);
    if (!ch || !isHttpUrl(s.url) || ch.closed) continue;
    if (CONFIG.exclude_nsfw && ch.is_nsfw) continue;
    const code = String(ch.country || "").toUpperCase();
    if (!/^[A-Z]{2}$/.test(code)) continue;
    out.push({
      code,
      name: ch.name,
      logo: (bestLogo.get(ch.id) || {}).url || ch.logo || "",
      url: s.url.trim(),
      quality: qualityScore(s.quality, s.title),
      source: "iptv-org",
    });
  }
  return out;
}

async function fromTdtChannels() {
  const data = await download(SOURCES.tdtChannels, { json: true });
  const out = [];
  for (const country of (data && data.countries) || []) {
    const code = countryCodeFromName(country && country.name);
    if (!code) continue;
    for (const ambit of country.ambits || []) {
      for (const ch of (ambit && ambit.channels) || []) {
        for (const o of (ch && ch.options) || []) {
          if (!o || !isHttpUrl(o.url)) continue;
          if (!/m3u8/i.test(String(o.format || "")) && !/\.m3u8(\?|$)/i.test(o.url)) continue;
          out.push({
            code,
            name: String(ch.name || "").trim(),
            logo: ch.logo || "",
            url: o.url.trim(),
            quality: qualityScore(o.res, ch.name),
            source: "TDTChannels",
          });
        }
      }
    }
  }
  return out;
}

function fromM3U(text, sourceName) {
  const out = [];
  for (const it of parseM3U(text)) {
    if (!isHttpUrl(it.url)) continue;
    const code =
      (/^[A-Za-z]{2}$/.test(it.country) && it.country.toUpperCase()) ||
      countryCodeFromName(it.group) ||
      null;
    if (!code) continue;
    out.push({ code, name: it.name, logo: it.logo, url: it.url, quality: qualityScore(it.name), source: sourceName });
  }
  return out;
}

async function fromFreeTv() {
  return fromM3U(await download(SOURCES.freeTv), "Free-TV");
}

/* ------------------------------------------------------------------ */
/* Comprobar la señal                                                   */
/* ------------------------------------------------------------------ */

/* null si emite; si no, el motivo. Se leen como mucho 64 KB. */
async function probeStream(url) {
  const controller = new AbortController();
  const t = setTimeout(() => controller.abort(), CONFIG.check_timeout_seconds * 1000);
  try {
    const res = await fetch(url, {
      signal: controller.signal,
      redirect: "follow",
      headers: { "User-Agent": TV_USER_AGENT },
    });
    if (!res.ok) return `error ${res.status}`;
    const reader = res.body && res.body.getReader();
    if (!reader) return "no envía nada";
    const chunks = [];
    let size = 0;
    while (size < 64 * 1024) {
      const { done, value } = await reader.read();
      if (done) break;
      chunks.push(value);
      size += value.length;
    }
    reader.cancel().catch(() => {});
    if (!size) return "no envía nada";
    const head = Buffer.concat(chunks).toString("utf8").replace(/^[﻿\s]+/, "");
    if (head.startsWith("#EXTM3U")) {
      const hasContent = head.split(/\r?\n/).some((l) => l.trim() && !l.trim().startsWith("#"));
      return hasContent ? null : "lista vacía";
    }
    if (head.startsWith("<")) return "contesta una página web, no vídeo";
    return null;
  } catch (err) {
    return err.name === "AbortError" ? `no contesta en ${CONFIG.check_timeout_seconds}s` : "no se puede conectar";
  } finally {
    clearTimeout(t);
    controller.abort();
  }
}

async function runPool(tasks, concurrency) {
  let next = 0;
  async function worker() {
    while (next < tasks.length) {
      const i = next++;
      await tasks[i]();
    }
  }
  await Promise.all(Array.from({ length: Math.min(concurrency, tasks.length) }, worker));
}

/* ------------------------------------------------------------------ */
/* Supabase                                                             */
/* ------------------------------------------------------------------ */

function sbHeaders(extra = {}) {
  return { apikey: SUPABASE_KEY, Authorization: `Bearer ${SUPABASE_KEY}`, "Content-Type": "application/json", ...extra };
}

async function sbFetchAllChannels() {
  const all = [];
  for (let offset = 0; ; offset += 1000) {
    const res = await fetch(
      `${SUPABASE_URL}/rest/v1/bt_channels?select=id,name,category,stream_url,channel_number,is_broken&order=id`,
      { headers: sbHeaders({ "Range-Unit": "items", Range: `${offset}-${offset + 999}` }) }
    );
    if (!res.ok) throw new Error(`Supabase no deja leer los canales (HTTP ${res.status}): ${await res.text()}`);
    const batch = await res.json();
    all.push(...batch);
    if (batch.length < 1000) break;
  }
  return all;
}

async function sbInsert(rows) {
  for (let i = 0; i < rows.length; i += 500) {
    const res = await fetch(`${SUPABASE_URL}/rest/v1/bt_channels`, {
      method: "POST",
      headers: sbHeaders({ Prefer: "return=minimal" }),
      body: JSON.stringify(rows.slice(i, i + 500)),
    });
    if (!res.ok) throw new Error(`No se pudieron añadir canales (HTTP ${res.status}): ${await res.text()}`);
  }
}

async function sbUpdate(id, values) {
  const res = await fetch(`${SUPABASE_URL}/rest/v1/bt_channels?id=eq.${encodeURIComponent(id)}`, {
    method: "PATCH",
    headers: sbHeaders({ Prefer: "return=minimal" }),
    body: JSON.stringify(values),
  });
  if (!res.ok) throw new Error(`No se pudo arreglar un canal (HTTP ${res.status}): ${await res.text()}`);
}

/* ------------------------------------------------------------------ */
/* Programa principal                                                   */
/* ------------------------------------------------------------------ */

async function collectCandidates(report) {
  const jobs = [
    ["iptv-org", fromIptvOrg],
    ["TDTChannels", fromTdtChannels],
    ["Free-TV", fromFreeTv],
    ...CONFIG.extra_m3u_sources
      .filter((s) => s && isHttpUrl(s.url))
      .map((s) => [s.name || s.url, async () => fromM3U(await download(s.url), s.name || s.url)]),
  ];
  const results = await Promise.allSettled(jobs.map(([, fn]) => fn()));
  const candidates = [];
  results.forEach((r, i) => {
    const name = jobs[i][0];
    if (r.status === "fulfilled") {
      report.sources.push({ name, ok: true, count: r.value.length });
      candidates.push(...r.value);
    } else {
      report.sources.push({ name, ok: false, error: String((r.reason && r.reason.message) || r.reason) });
    }
  });
  const only = new Set((CONFIG.countries || []).map((c) => String(c).toUpperCase()));
  return only.size ? candidates.filter((c) => only.has(c.code)) : candidates;
}

async function main() {
  const report = {
    startedAt: new Date(),
    status: "ÉXITO",
    sources: [],
    analyzed: 0,
    tested: 0,
    working: 0,
    added: 0,
    repaired: 0,
    alreadyHad: 0,
    noSignal: 0,
    brokenInDb: 0,
    totalInDb: 0,
    byCountry: new Map(),
    notes: [],
  };

  if (!SUPABASE_KEY && !DRY_RUN) {
    throw new Error("Falta el secreto SUPABASE_SERVICE_ROLE_KEY en GitHub (Settings → Secrets → Actions).");
  }

  const candidates = await collectCandidates(report);
  if (report.sources.some((s) => !s.ok)) report.status = "ADVERTENCIA";
  if (!candidates.length) throw new Error("Ninguna fuente ha devuelto canales.");

  // Agrupar por país + nombre: cada grupo es un canal con uno o varios enlaces.
  const groups = new Map();
  for (const c of candidates) {
    const category = spanishCountryName(c.code) || c.code;
    const key = channelNameKey(c.name, category);
    if (!key) continue;
    if (!groups.has(key)) groups.set(key, { key, code: c.code, category, name: displayName(c.name) || c.name, logo: "", options: [] });
    const g = groups.get(key);
    if (!g.options.some((o) => o.url === c.url)) g.options.push(c);
    if (!g.logo && isHttpUrl(c.logo)) g.logo = c.logo;
  }
  report.analyzed = groups.size;

  const existing = DRY_RUN && !SUPABASE_KEY ? [] : await sbFetchAllChannels();
  report.totalInDb = existing.length;
  report.brokenInDb = existing.filter((c) => c.is_broken).length;
  const existingUrls = new Set(existing.map((c) => String(c.stream_url || "").trim()));
  const existingByKey = new Map();
  for (const c of existing) {
    const k = channelNameKey(c.name, c.category);
    if (k && !existingByKey.has(k)) existingByKey.set(k, c);
  }

  // Qué grupos hay que probar.
  const toTest = [];
  for (const g of groups.values()) {
    const mine = existingByKey.get(g.key);
    const urlAlready = g.options.some((o) => existingUrls.has(o.url));
    if ((mine && !mine.is_broken) || (urlAlready && !mine)) {
      report.alreadyHad += 1;
      continue;
    }
    // Primero el enlace de más calidad; si falla, el siguiente.
    g.options.sort((a, b) => b.quality - a.quality);
    g.repairOf = mine && mine.is_broken ? mine : null;
    if (g.repairOf) g.options = g.options.filter((o) => o.url !== String(g.repairOf.stream_url || "").trim());
    if (g.options.length) toTest.push(g);
  }

  await runPool(
    toTest.map((g) => async () => {
      for (const o of g.options) {
        report.tested += 1;
        const problem = await probeStream(o.url);
        if (!problem) {
          g.winner = o;
          report.working += 1;
          return;
        }
      }
      report.noSignal += 1;
    }),
    CONFIG.check_concurrency
  );

  // Freno de seguridad: si casi nada responde, el problema es la red del
  // servidor de GitHub, no los canales. No se toca nada.
  if (report.tested >= 200 && report.working / toTest.length < 0.05) {
    throw new Error(
      `Solo ${report.working} de ${toTest.length} canales responden: parece un fallo de red, no se ha tocado nada.`
    );
  }

  // Numeración: cada país sigue donde se quedó.
  const nextNumber = new Map();
  for (const c of existing) {
    const k = String(c.category || "").trim() || "Sin categoría";
    nextNumber.set(k, Math.max(nextNumber.get(k) || 0, Number(c.channel_number) || 0));
  }
  const now = new Date().toISOString();
  const newRows = [];
  const repairs = [];
  for (const g of toTest) {
    if (!g.winner) continue;
    if (g.repairOf) {
      repairs.push({ id: g.repairOf.id, values: { stream_url: g.winner.url, is_broken: false, last_checked_at: now }, g });
      continue;
    }
    if (CONFIG.max_new_per_run && newRows.length >= CONFIG.max_new_per_run) continue;
    if (existingUrls.has(g.winner.url)) continue;
    existingUrls.add(g.winner.url);
    const n = (nextNumber.get(g.category) || 0) + 1;
    nextNumber.set(g.category, n);
    newRows.push({
      channel_number: n,
      name: g.name,
      category: g.category,
      logo_url: g.logo || null,
      stream_url: g.winner.url,
      is_broken: false,
      last_checked_at: now,
      _g: g,
    });
  }

  if (!DRY_RUN) {
    await sbInsert(newRows.map(({ _g, ...row }) => row));
    for (const r of repairs) await sbUpdate(r.id, r.values);
  } else {
    report.notes.push("Modo prueba (DRY_RUN): no se ha escrito nada en Supabase.");
  }
  report.added = newRows.length;
  report.repaired = repairs.length;

  for (const row of newRows) bumpCountry(report, row._g, "added");
  for (const r of repairs) bumpCountry(report, r.g, "repaired");
  return report;
}

function bumpCountry(report, g, field) {
  if (!report.byCountry.has(g.code)) {
    report.byCountry.set(g.code, { code: g.code, name: g.category, flag: flagFromCode(g.code), added: 0, repaired: 0 });
  }
  report.byCountry.get(g.code)[field] += 1;
}

/* ------------------------------------------------------------------ */
/* Informe                                                              */
/* ------------------------------------------------------------------ */

function escapeHtml(s) {
  return String(s).replace(/[&<>"]/g, (ch) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[ch]));
}

function buildReport(report, error) {
  const status = error ? "ERROR" : report.status;
  const minutes = Math.round((Date.now() - report.startedAt.getTime()) / 60000);
  const countries = [...report.byCountry.values()].sort((a, b) => b.added + b.repaired - (a.added + a.repaired));
  const lines = [
    `Estado: ${status}`,
    error ? `Error: ${error.message}` : null,
    `Duración: ${minutes} min`,
    "",
    "Fuentes:",
    ...report.sources.map((s) => `  - ${s.name}: ${s.ok ? `${s.count} enlaces` : `NO SE PUDO LEER (${s.error})`}`),
    "",
    `Canales distintos encontrados: ${report.analyzed}`,
    `Ya los tenías: ${report.alreadyHad}`,
    `Enlaces probados: ${report.tested}`,
    `Canales nuevos añadidos: ${report.added}`,
    `Canales caídos arreglados con otro enlace: ${report.repaired}`,
    `Descartados por no tener señal: ${report.noSignal}`,
    `En la base de datos: ${report.totalInDb} canales, ${report.brokenInDb} marcados como caídos por el revisor nocturno`,
    "",
    countries.length ? "Por país:" : "Ningún país ha cambiado hoy.",
    ...countries.map((c) => `  ${c.flag} ${c.name}: +${c.added} nuevos${c.repaired ? `, ${c.repaired} arreglados` : ""}`),
    ...report.notes.map((n) => `\n${n}`),
  ].filter((l) => l !== null);
  const text = lines.join("\n");
  const color = status === "ÉXITO" ? "#1a7f37" : status === "ADVERTENCIA" ? "#9a6700" : "#cf222e";
  const html =
    `<div style="font-family:Arial,sans-serif;font-size:14px">` +
    `<h2 style="color:${color};margin:0 0 8px">Boughazi-TV · Robot de canales: ${escapeHtml(status)}</h2>` +
    `<pre style="font-family:inherit;white-space:pre-wrap">${escapeHtml(text)}</pre></div>`;
  const subject = `[Boughazi-TV] Canales ${status}: +${report.added} nuevos, ${report.repaired} arreglados`;
  return { subject, text, html, status };
}

async function sendEmail({ subject, text, html }) {
  const user = (process.env.GMAIL_USER || "").trim();
  const pass = (process.env.GMAIL_PASS || process.env.GMAIL_APP_PASSWORD || "").replace(/\s+/g, "");
  const to = (process.env.NOTIFICATION_EMAIL || "").trim() || user;
  if (!user || !pass) {
    console.log("No se envía el correo: faltan los secretos GMAIL_USER y GMAIL_APP_PASSWORD.");
    return;
  }
  const nodemailer = require("nodemailer");
  const transport = nodemailer.createTransport({ service: "gmail", auth: { user, pass } });
  await transport.sendMail({ from: `Boughazi-TV <${user}>`, to, subject, text, html });
  console.log(`Informe enviado a ${to}.`);
}

function writeJobSummary({ text }) {
  if (!process.env.GITHUB_STEP_SUMMARY) return;
  fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, "```\n" + text + "\n```\n");
}

if (require.main === module) {
  (async () => {
    let report;
    let error = null;
    try {
      report = await main();
    } catch (err) {
      error = err;
      report = report || {
        startedAt: new Date(), status: "ERROR", sources: [], analyzed: 0, tested: 0, working: 0, added: 0,
        repaired: 0, alreadyHad: 0, noSignal: 0, brokenInDb: 0, totalInDb: 0, byCountry: new Map(), notes: [],
      };
    }
    const out = buildReport(report, error);
    console.log(out.text);
    writeJobSummary(out);
    try {
      await sendEmail(out);
    } catch (err) {
      console.log(`No se pudo enviar el correo: ${err.message}`);
      if (!error) process.exitCode = 1;
    }
    if (error) process.exitCode = 1;
  })();
}

module.exports = { channelNameKey, countryCodeFromName, qualityScore, parseM3U, probeStream, main, buildReport };
