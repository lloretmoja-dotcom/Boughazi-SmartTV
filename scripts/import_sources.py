"""
Listas automáticas: vuelve a importar cada noche las listas guardadas en
la tabla bt_auto_sources (pestaña "Listas automáticas" del panel) y añade
a bt_channels SOLO los canales nuevos. No borra ni cambia los canales que
ya existen: de eso se encarga check_channels.py, que se ejecuta después.

Cada lista puede ser:
  - 'm3u': un enlace a una lista M3U / M3U8.
  - 'xtream': servidor + usuario + contraseña de Xtream Codes.

Un canal se considera "ya existente" si su enlace de vídeo (stream_url) ya
está en bt_channels, exactamente igual (sin pasar a minúsculas: dos
enlaces que solo se distinguen en una mayúscula son canales distintos).

Igual que check_channels.py, usa la "service_role key" (secreto
SUPABASE_SERVICE_ROLE_KEY de GitHub), nunca la clave pública.
"""

import asyncio
import datetime
import json
import os
import sys
import urllib.parse

import aiohttp

SUPABASE_URL = os.environ.get("SUPABASE_URL", "https://oansihwqjcfjackfhgbd.supabase.co").rstrip("/")
SUPABASE_SERVICE_ROLE_KEY = os.environ.get("SUPABASE_SERVICE_ROLE_KEY", "").strip()

PAGE_SIZE = 1000
INSERT_CHUNK = 500
DOWNLOAD_TIMEOUT_SECONDS = 60
MAX_LIST_BYTES = 20 * 1024 * 1024


def headers(extra=None):
    h = {
        "apikey": SUPABASE_SERVICE_ROLE_KEY,
        "Authorization": f"Bearer {SUPABASE_SERVICE_ROLE_KEY}",
        "Content-Type": "application/json",
    }
    if extra:
        h.update(extra)
    return h


def is_http_url(value):
    try:
        u = urllib.parse.urlparse((value or "").strip())
        return u.scheme in ("http", "https") and bool(u.netloc)
    except ValueError:
        return False


def first_comma_outside_quotes(line):
    in_quotes = False
    for i, ch in enumerate(line):
        if ch == '"':
            in_quotes = not in_quotes
        elif ch == "," and not in_quotes:
            return i
    return -1


def attr(attrs, name):
    key = f'{name}="'
    lower = attrs.lower()
    start = lower.find(key)
    if start < 0:
        return ""
    start += len(key)
    end = attrs.find('"', start)
    return attrs[start:end] if end >= 0 else ""


def parse_m3u(text):
    """Mismo criterio que el panel: #EXTINF + enlace, o un enlace por línea."""
    items = []
    pending = None
    for raw in text.lstrip("﻿").splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.upper().startswith("#EXTINF"):
            comma = first_comma_outside_quotes(line)
            attrs = line[:comma] if comma >= 0 else line
            name = line[comma + 1:].strip() if comma >= 0 else ""
            pending = {
                "name": name or f"Canal {len(items) + 1}",
                "category": attr(attrs, "group-title"),
                "logo": attr(attrs, "tvg-logo"),
            }
            continue
        if line.startswith("#"):
            continue
        if pending:
            items.append({**pending, "url": line})
            pending = None
        else:
            items.append({"name": f"Canal {len(items) + 1}", "category": "", "logo": "", "url": line})
    return items


async def download_text(session, url):
    timeout = aiohttp.ClientTimeout(total=DOWNLOAD_TIMEOUT_SECONDS)
    async with session.get(url, timeout=timeout, allow_redirects=True) as resp:
        if resp.status >= 400:
            raise RuntimeError(f"el servidor ha contestado con el error {resp.status}")
        data = bytearray()
        async for chunk in resp.content.iter_chunked(64 * 1024):
            data.extend(chunk)
            if len(data) > MAX_LIST_BYTES:
                raise RuntimeError("la lista es demasiado grande (más de 20 MB)")
        return data.decode("utf-8", errors="replace")


async def load_xtream(session, server, username, password):
    server = (server or "").strip().rstrip("/")
    if not is_http_url(server):
        raise RuntimeError("el servidor de Xtream tiene que empezar por http:// o https://")
    base = (
        f"{server}/player_api.php?username={urllib.parse.quote(username or '', safe='')}"
        f"&password={urllib.parse.quote(password or '', safe='')}"
    )
    try:
        categories = json.loads(await download_text(session, base + "&action=get_live_categories"))
        streams = json.loads(await download_text(session, base + "&action=get_live_streams"))
    except json.JSONDecodeError:
        raise RuntimeError("el servidor no ha devuelto una respuesta de Xtream Codes (¿usuario o contraseña mal?)")
    if not isinstance(streams, list):
        raise RuntimeError("usuario o contraseña de Xtream incorrectos")
    names = {}
    if isinstance(categories, list):
        names = {str(c.get("category_id")): c.get("category_name") or "" for c in categories if isinstance(c, dict)}
    user_q = urllib.parse.quote(username or "", safe="")
    pass_q = urllib.parse.quote(password or "", safe="")
    items = []
    for s in streams:
        if not isinstance(s, dict) or s.get("stream_id") is None:
            continue
        items.append({
            "name": s.get("name") or f"Canal {len(items) + 1}",
            "category": names.get(str(s.get("category_id")), ""),
            "logo": s.get("stream_icon") or "",
            "url": f"{server}/live/{user_q}/{pass_q}/{s['stream_id']}.m3u8",
        })
    return items


async def fetch_all(session, path):
    rows = []
    offset = 0
    while True:
        h = headers({"Range-Unit": "items", "Range": f"{offset}-{offset + PAGE_SIZE - 1}"})
        async with session.get(f"{SUPABASE_URL}/rest/v1/{path}", headers=h) as resp:
            if resp.status >= 300:
                raise RuntimeError(f"Supabase {resp.status}: {await resp.text()}")
            page = await resp.json()
        rows.extend(page)
        if len(page) < PAGE_SIZE:
            return rows
        offset += PAGE_SIZE


async def update_source(session, source_id, result):
    body = {"last_run_at": datetime.datetime.now(datetime.timezone.utc).isoformat(), "last_result": result[:500]}
    async with session.patch(
        f"{SUPABASE_URL}/rest/v1/bt_auto_sources?id=eq.{source_id}", headers=headers(), json=body
    ) as resp:
        if resp.status >= 300:
            print(f"  (no se pudo guardar el resultado: {resp.status})")


def category_key(category):
    return (category or "").strip() or "Sin categoría"


async def main():
    if not SUPABASE_SERVICE_ROLE_KEY:
        print("::error::Falta el secreto SUPABASE_SERVICE_ROLE_KEY.")
        return 1

    async with aiohttp.ClientSession() as session:
        try:
            sources = await fetch_all(session, "bt_auto_sources?select=*&enabled=eq.true&order=id.asc")
        except RuntimeError as e:
            if "bt_auto_sources" in str(e) and ("404" in str(e) or "PGRST205" in str(e) or "42P01" in str(e)):
                print("La tabla bt_auto_sources todavía no existe (falta ejecutar el SQL). Nada que hacer.")
                return 0
            raise

        if not sources:
            print("No hay listas automáticas activadas.")
            return 0

        existing = await fetch_all(session, "bt_channels?select=stream_url,category,channel_number")
        seen = {(c.get("stream_url") or "").strip() for c in existing}
        next_number = {}
        for c in existing:
            key = category_key(c.get("category"))
            next_number[key] = max(next_number.get(key, 1), (c.get("channel_number") or 0) + 1)

        failures = 0
        for src in sources:
            label = src.get("name") or f"lista {src['id']}"
            print(f"- {label} ({src['kind']})")
            try:
                if src["kind"] == "xtream":
                    items = await load_xtream(
                        session, src.get("xtream_server"), src.get("xtream_username"), src.get("xtream_password")
                    )
                else:
                    if not is_http_url(src.get("url")):
                        raise RuntimeError("el enlace tiene que empezar por http:// o https://")
                    items = parse_m3u(await download_text(session, src["url"].strip()))

                rows = []
                for it in items:
                    url = (it["url"] or "").strip()
                    if not is_http_url(url) or url in seen:
                        continue
                    seen.add(url)
                    category = (src.get("category_override") or "").strip() or it["category"] or None
                    key = category_key(category)
                    number = next_number.get(key, 1)
                    next_number[key] = number + 1
                    rows.append({
                        "channel_number": number,
                        "name": it["name"],
                        "category": category,
                        "logo_url": it["logo"] if is_http_url(it["logo"]) else None,
                        "stream_url": url,
                    })

                for i in range(0, len(rows), INSERT_CHUNK):
                    async with session.post(
                        f"{SUPABASE_URL}/rest/v1/bt_channels",
                        headers=headers({"Prefer": "return=minimal"}),
                        json=rows[i:i + INSERT_CHUNK],
                    ) as resp:
                        if resp.status >= 300:
                            raise RuntimeError(
                                f"no se pudieron guardar los canales ({resp.status}); "
                                f"se habían añadido {i} de {len(rows)}"
                            )

                result = f"OK: {len(items)} canales en la lista, {len(rows)} nuevos añadidos"
            except Exception as e:  # noqa: BLE001 — se apunta y se sigue con la siguiente lista
                failures += 1
                result = f"Error: {e}"
            print(f"  {result}")
            await update_source(session, src["id"], result)

        # Una lista caída no debe impedir comprobar los canales después.
        if failures:
            print(f"::warning::{failures} lista(s) automática(s) han fallado. Mira el resultado en el panel.")
        return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
