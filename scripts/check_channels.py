"""
Comprueba, uno por uno, si el enlace de vídeo (stream_url) de cada canal
de la tabla bt_channels responde de verdad, y BORRA PARA SIEMPRE de la
base de datos (sin dejar rastro, no solo ocultos):

  - los canales que fallan dos veces seguidas (con una pequeña espera
    entre intento e intento, por si ha sido solo un corte de red
    pasajero y no un canal de verdad muerto)
  - los canales duplicados: cuando dos o más filas tienen exactamente
    el mismo enlace de vídeo, solo se queda una (la que tiene número
    de canal asignado, o si no la más antigua) y se borran las demás

IMPORTANTE — por qué antes "no funcionaba de verdad":
Cuando se protegieron las tablas de Supabase con seguridad a nivel de
fila (RLS), se dejó que solo una cuenta de administrador identificada
pudiera leer y escribir en bt_channels. Este script, en cambio, se
conectaba con la "anon key" (la clave pública, la misma que lleva la
aplicación de la tele dentro), que desde ese cambio de seguridad ya NO
cuenta como administrador — así que Supabase respondía "todo bien"
(HTTP 200) pero en realidad no tocaba ni una fila. Por eso los canales
caídos se quedaban ahí para siempre, aunque en la pantalla pareciera
que el sistema funcionaba.

La solución es usar aquí la "service_role key": una clave secreta de
Supabase que se salta esa protección (por eso NUNCA debe ir dentro de
la aplicación ni subirse a GitHub en un archivo normal — solo vive
como un "secreto" del repositorio, ver check-channels.yml).
"""

import asyncio
import datetime
import os
import sys

import aiohttp

SUPABASE_URL = os.environ.get("SUPABASE_URL", "https://oansihwqjcfjackfhgbd.supabase.co").rstrip("/")
SUPABASE_SERVICE_ROLE_KEY = os.environ.get("SUPABASE_SERVICE_ROLE_KEY", "").strip()

CONCURRENCY = 50
REQUEST_TIMEOUT_SECONDS = 10
PAGE_SIZE = 1000
SEGUNDA_ESPERA_SEGUNDOS = 3  # pausa antes del segundo intento, por si el corte era pasajero


def _now_iso():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def _headers():
    return {
        "apikey": SUPABASE_SERVICE_ROLE_KEY,
        "Authorization": f"Bearer {SUPABASE_SERVICE_ROLE_KEY}",
    }


async def fetch_all_channels(session):
    """Trae TODOS los canales, en tandas de 1000."""
    channels = []
    offset = 0
    while True:
        url = (
            f"{SUPABASE_URL}/rest/v1/bt_channels"
            "?select=id,name,stream_url,channel_number,is_broken"
        )
        headers = {
            **_headers(),
            "Range-Unit": "items",
            "Range": f"{offset}-{offset + PAGE_SIZE - 1}",
        }
        async with session.get(url, headers=headers) as resp:
            resp.raise_for_status()
            batch = await resp.json()
        channels.extend(batch)
        if len(batch) < PAGE_SIZE:
            break
        offset += PAGE_SIZE
    return channels


def find_duplicates(channels):
    """Agrupa los canales por su enlace de vídeo (normalizado: sin
    espacios ni mayúsculas/minúsculas). De cada grupo con más de un
    canal, se queda con uno solo (el que tenga número de canal
    asignado; si ninguno lo tiene, el más antiguo por id) y devuelve
    los ids de los demás, que son duplicados de verdad y se pueden
    borrar sin miedo."""
    groups = {}
    for ch in channels:
        key = (ch.get("stream_url") or "").strip().lower()
        if not key:
            continue
        groups.setdefault(key, []).append(ch)

    duplicate_ids = []
    for key, group in groups.items():
        if len(group) < 2:
            continue
        group_sorted = sorted(group, key=lambda c: (c.get("channel_number") is None, c["id"]))
        # group_sorted[0] es el que se queda; el resto se borra.
        duplicate_ids.extend(c["id"] for c in group_sorted[1:])
    return duplicate_ids


# Cabecera que imita a un reproductor de vídeo de verdad (VLC). Muchos
# servidores de streaming rechazan o devuelven una página de error a
# peticiones que no llevan un "user-agent" de reproductor conocido —
# antes esto podía hacer que un canal que SÍ funciona en la aplicación
# pareciera caído solo por cómo se hacía la comprobación.
PLAYER_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

# Cuánto contenido real del enlace se descarga para comprobar que es un
# vídeo/lista de canales de verdad, y no una página de error disfrazada
# de "200 OK". No hace falta bajar el archivo entero.
CONTENT_CHECK_BYTES = 4096


def _looks_like_real_stream(url, content_type, body_bytes):
    """Un canal puede responder con HTTP 200 (código "todo bien") y aun
    así no servir para nada: una página de error, un aviso de "canal no
    disponible en tu país", una lista de reproducción vacía, etc. Esta
    función mira el contenido de verdad para no dejarse engañar por el
    código de estado."""
    text_start = body_bytes[:CONTENT_CHECK_BYTES].decode("utf-8", errors="ignore").strip()

    es_lista_m3u = url.lower().split("?")[0].endswith((".m3u8", ".m3u"))
    if es_lista_m3u:
        # Una lista de canales/segmentos de verdad SIEMPRE empieza por
        # esta cabecera. Si no, es casi seguro una página de error o un
        # inicio de sesión, aunque el servidor haya dicho "200 OK".
        return text_start.upper().startswith("#EXTM3U") and len(text_start) > 15

    # Para enlaces que no son .m3u8 (vídeo directo u otro formato): si
    # el "content-type" dice que es una página web (html/json/texto
    # normal) en vez de vídeo o audio, es casi seguro un error disfrazado.
    content_type = (content_type or "").lower()
    if "text/html" in content_type or "application/json" in content_type:
        return False

    # Y si no ha devuelto prácticamente nada de contenido, tampoco vale.
    return len(body_bytes) > 32


async def check_one(session, sem, channel):
    """Devuelve True si el canal responde bien Y de verdad sirve
    contenido real (no una página de error disfrazada de "200 OK"). Se
    hace un segundo intento (con una pequeña espera) antes de dar un
    canal por caído de verdad, por si ha sido solo un corte momentáneo."""
    url = (channel.get("stream_url") or "").strip()
    if not url.startswith("http"):
        return False

    async def attempt():
        try:
            timeout = aiohttp.ClientTimeout(total=REQUEST_TIMEOUT_SECONDS)
            headers = {"User-Agent": PLAYER_USER_AGENT}
            async with session.get(url, timeout=timeout, allow_redirects=True, headers=headers) as resp:
                if not resp.ok:
                    return False
                content_type = resp.headers.get("Content-Type", "")
                body = await resp.content.read(CONTENT_CHECK_BYTES)
                return _looks_like_real_stream(url, content_type, body)
        except Exception:
            return False

    async with sem:
        if await attempt():
            return True

    await asyncio.sleep(SEGUNDA_ESPERA_SEGUNDOS)

    async with sem:
        return await attempt()


async def delete_channels(session, sem, ids, motivo):
    """Borra canales PARA SIEMPRE de la base de datos — no los oculta,
    los elimina de verdad, sin dejar rastro."""
    if not ids:
        return

    async def delete_one(cid):
        url = f"{SUPABASE_URL}/rest/v1/bt_channels?id=eq.{cid}"
        async with sem:
            async with session.delete(url, headers=_headers()) as resp:
                if resp.status not in (200, 204):
                    body = await resp.text()
                    print(f"  ! No se pudo borrar el canal {cid}: HTTP {resp.status} — {body}")

    await asyncio.gather(*(delete_one(cid) for cid in ids))
    print(f"Borrados para siempre ({motivo}): {len(ids)}")


async def mark_checked(session, sem, ids):
    """A los canales que SÍ funcionan se les anota la fecha de la
    última comprobación, para el aviso del panel de administración."""
    if not ids:
        return

    async def touch(cid):
        url = f"{SUPABASE_URL}/rest/v1/bt_channels?id=eq.{cid}"
        headers = {**_headers(), "Content-Type": "application/json", "Prefer": "return=minimal"}
        payload = {"is_broken": False, "last_checked_at": _now_iso()}
        async with sem:
            async with session.patch(url, headers=headers, json=payload) as resp:
                if resp.status not in (200, 204):
                    body = await resp.text()
                    print(f"  ! No se pudo actualizar el canal {cid}: HTTP {resp.status} — {body}")

    await asyncio.gather(*(touch(cid) for cid in ids))


async def main():
    if not SUPABASE_SERVICE_ROLE_KEY:
        print(
            "Falta la variable SUPABASE_SERVICE_ROLE_KEY. Sin ella (guardada como "
            "secreto del repositorio en GitHub), este script no tiene permiso real "
            "para borrar ni actualizar nada en Supabase — solo podría leer, y ni eso "
            "si la lectura también está protegida."
        )
        sys.exit(1)

    connector = aiohttp.TCPConnector(limit=CONCURRENCY)
    async with aiohttp.ClientSession(connector=connector) as session:
        print("Descargando la lista completa de canales…")
        channels = await fetch_all_channels(session)
        print(f"Canales encontrados en la base de datos: {len(channels)}")

        sem = asyncio.Semaphore(CONCURRENCY)

        # 1) Duplicados exactos (mismo enlace de vídeo) — se borran ya,
        # antes incluso de comprobar si funcionan, porque son sobrantes
        # sin más.
        duplicate_ids = find_duplicates(channels)
        if duplicate_ids:
            print(f"\nCanales duplicados encontrados: {len(duplicate_ids)}")
            await delete_channels(session, sem, duplicate_ids, "duplicados")
            dup_set = set(duplicate_ids)
            channels = [c for c in channels if c["id"] not in dup_set]
        else:
            print("\nNo se han encontrado canales duplicados.")

        # 2) Comprobar de verdad si cada canal restante funciona.
        print(f"\nComprobando si funcionan de verdad {len(channels)} canales…")
        results = await asyncio.gather(*(check_one(session, sem, ch) for ch in channels))

        dead = [ch for ch, alive in zip(channels, results) if not alive]
        alive_ids = [ch["id"] for ch, alive in zip(channels, results) if alive]

        if dead:
            print(f"\nCanales caídos que se borran para siempre: {len(dead)}")
            for ch in dead:
                print(f"  - {ch.get('name')} ({ch['id']})")
            await delete_channels(session, sem, [ch["id"] for ch in dead], "caídos")
        else:
            print("\nNo hay canales caídos ahora mismo.")

        # 3) A los que sí funcionan, se les anota cuándo se comprobaron.
        await mark_checked(session, sem, alive_ids)

        print("\nResumen final:")
        print(f"  Duplicados borrados: {len(duplicate_ids)}")
        print(f"  Caídos borrados: {len(dead)}")
        print(f"  Funcionando de verdad ahora mismo: {len(alive_ids)}")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except Exception as e:
        print(f"Fallo al comprobar los canales: {e}")
        sys.exit(1)
