"""
Comprueba, uno por uno, si el enlace de vídeo (stream_url) de cada canal
de la tabla bt_channels responde de verdad, y:

  - los canales que fallan (dos intentos seguidos, con una pequeña espera
    entre uno y otro) se marcan como CAÍDOS (is_broken = true). La app de
    la tele ya no los muestra, pero siguen en la base de datos por si
    vuelven: si a la noche siguiente responden, se reactivan solos.
  - solo se BORRAN para siempre los que llevan más de
    DIAS_ANTES_DE_BORRAR días seguidos caídos (se sabe por la fecha
    "last_checked_at", que se pone al día cada noche que el canal SÍ
    funciona).
  - los canales duplicados (exactamente el mismo enlace de vídeo) se
    borran, quedándose uno (el que tiene número de canal asignado, o si no
    el más antiguo).

POR QUÉ YA NO SE BORRA A LA PRIMERA: esta comprobación se hace desde los
servidores de GitHub (en EE. UU.). Muchos canales están bloqueados por
país, o fallan un momento a las 3 de la mañana, y aun así funcionan en
la tele de la gente. Antes se perdían para siempre por eso.

FRENO DE SEGURIDAD: si de golpe falla más del PORCENTAJE_MAXIMO_CAIDOS %
de los canales, casi seguro el problema es de la red del servidor de
GitHub o de Supabase, no de los canales. En ese caso el script NO toca
nada y termina en rojo para que se vea en la pestaña "Actions".

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
import urllib.parse

import aiohttp

SUPABASE_URL = os.environ.get("SUPABASE_URL", "https://oansihwqjcfjackfhgbd.supabase.co").rstrip("/")
SUPABASE_SERVICE_ROLE_KEY = os.environ.get("SUPABASE_SERVICE_ROLE_KEY", "").strip()

CONCURRENCY = 50
REQUEST_TIMEOUT_SECONDS = 10
PAGE_SIZE = 1000
SEGUNDA_ESPERA_SEGUNDOS = 3  # pausa antes del segundo intento, por si el corte era pasajero
DIAS_ANTES_DE_BORRAR = 7  # días seguidos caído antes de borrarlo para siempre
PORCENTAJE_MAXIMO_CAIDOS = 30  # si falla más que esto, no se toca nada (freno de seguridad)
MINIMO_CANALES_PARA_FRENO = 20  # con muy pocos canales el porcentaje no dice nada
ESPERA_MAXIMA_CONGELADO = 12  # segundos que se espera para ver si un directo avanza
TANDA_ACTUALIZACION = 200  # ids por petición al marcar/borrar en Supabase


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
            "?select=id,name,category,stream_url,channel_number,is_broken,last_checked_at"
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
    espacios). De cada grupo con más de un
    canal, se queda con uno solo (el que tenga número de canal
    asignado; si ninguno lo tiene, el más antiguo por id) y devuelve
    los ids de los demás, que son duplicados de verdad y se pueden
    borrar sin miedo."""
    groups = {}
    for ch in channels:
        # Solo se quitan los espacios: muchos enlaces llevan un "token" en
        # el que mayúsculas y minúsculas importan, así que dos enlaces que
        # solo se diferencian en eso son canales distintos.
        key = (ch.get("stream_url") or "").strip()
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
CONTENT_CHECK_BYTES = 8192


def _es_lista_m3u(url):
    return url.lower().split("?")[0].endswith((".m3u8", ".m3u"))


def _analizar_playlist(texto):
    """Mira el contenido de una lista .m3u8 de verdad y dice si:
      - no es una lista válida (es una página de error, un login, etc.)
      - es una "lista maestra" que apunta a otra lista con el vídeo real
        (devuelve esa otra dirección para seguirla)
      - es la lista final y SÍ tiene al menos un trozo de vídeo dentro
        (un canal "muerto" muchas veces responde con el formato correcto
        pero totalmente vacío, sin ningún trozo de vídeo listado)
    Devuelve una tupla: (es_valida, url_a_seguir_o_None)
    """
    texto = texto.strip()
    if not texto.upper().startswith("#EXTM3U"):
        return False, None

    lineas = [l.strip() for l in texto.splitlines() if l.strip()]
    tiene_variantes = any(l.upper().startswith("#EXT-X-STREAM-INF") for l in lineas)

    # La primera línea sin "#" después de la cabecera es el siguiente
    # paso: o bien otra lista (si es una "lista maestra"), o bien ya un
    # trozo de vídeo de verdad.
    siguiente = next((l for l in lineas[1:] if not l.startswith("#")), None)

    if tiene_variantes:
        # Lista maestra: solo vale si de verdad apunta a algo.
        return (siguiente is not None), siguiente

    # Lista final: solo vale si tiene al menos un trozo de vídeo listado.
    return (siguiente is not None), None


class Fallo(Exception):
    """Un canal que no funciona, con el motivo concreto que se guarda en
    el informe del panel ("Centro de control")."""

    def __init__(self, tipo, detalle):
        super().__init__(detalle)
        self.tipo = tipo  # error_http, sin_respuesta, bucle, vacio, congelado
        self.detalle = detalle


async def _descargar(session, url, headers):
    """Descarga el principio de un enlace. Devuelve (content_type, texto)
    o lanza Fallo con el motivo exacto (error 404/500, redirecciones en
    bucle, el servidor no contesta…)."""
    try:
        timeout = aiohttp.ClientTimeout(total=REQUEST_TIMEOUT_SECONDS)
        async with session.get(
            url, timeout=timeout, allow_redirects=True, max_redirects=5, headers=headers
        ) as resp:
            if not resp.ok:
                raise Fallo("error_http", f"El servidor contesta con el error {resp.status}")
            content_type = resp.headers.get("Content-Type", "")
            raw = await resp.content.read(CONTENT_CHECK_BYTES)
            return content_type, raw.decode("utf-8", errors="ignore")
    except Fallo:
        raise
    except aiohttp.TooManyRedirects:
        raise Fallo("bucle", "El enlace redirige en bucle sin llegar nunca al vídeo")
    except asyncio.TimeoutError:
        raise Fallo("sin_respuesta", f"No contesta en {REQUEST_TIMEOUT_SECONDS} segundos")
    except Exception as e:  # noqa: BLE001 — cualquier fallo de red cuenta como caído
        raise Fallo("sin_respuesta", f"No se puede conectar ({e.__class__.__name__})")


def _estado_directo(texto):
    """De una lista final de un directo saca lo que cambia cuando el vídeo
    avanza: el número de secuencia y el último trozo. Si es un vídeo
    grabado (#EXT-X-ENDLIST) devuelve None: no tiene por qué avanzar."""
    lineas = [l.strip() for l in texto.splitlines() if l.strip()]
    if any(l.upper().startswith("#EXT-X-ENDLIST") for l in lineas):
        return None, None
    secuencia = next(
        (l.split(":", 1)[1] for l in lineas if l.upper().startswith("#EXT-X-MEDIA-SEQUENCE:")), ""
    )
    duracion = next(
        (l.split(":", 1)[1] for l in lineas if l.upper().startswith("#EXT-X-TARGETDURATION:")), "6"
    )
    trozos = [l for l in lineas if not l.startswith("#")]
    try:
        espera = min(max(float(duracion) * 1.5, 4), ESPERA_MAXIMA_CONGELADO)
    except ValueError:
        espera = 9
    return (secuencia, trozos[-1] if trozos else ""), espera


async def _comprobar_contenido(session, url, headers, saltos_restantes=2):
    """Comprueba que un enlace de verdad sirve contenido de vídeo real,
    no solo que "conecta" (HTTP 200). Sigue una lista maestra hasta la
    lista final si hace falta, con un límite de saltos por seguridad.
    En los directos, además, vuelve a mirar la lista un poco después para
    ver si avanza: si sigue exactamente igual, la emisión está congelada.
    Si algo falla, lanza Fallo con el motivo."""
    content_type, texto = await _descargar(session, url, headers)

    if _es_lista_m3u(url) or (texto or "").strip().upper().startswith("#EXTM3U"):
        es_valida, url_a_seguir = _analizar_playlist(texto or "")
        if not es_valida:
            raise Fallo("vacio", "La lista no tiene vídeo (vacía o es una página de error)")
        if url_a_seguir:
            if saltos_restantes <= 0:
                raise Fallo("bucle", "Listas que apuntan a otras listas sin llegar nunca al vídeo")
            # La dirección de la siguiente lista puede venir en forma
            # relativa ("/algo.m3u8") — hay que unirla con la dirección
            # base para que sea una dirección completa de verdad.
            siguiente_url = urllib.parse.urljoin(url, url_a_seguir)
            return await _comprobar_contenido(session, siguiente_url, headers, saltos_restantes - 1)

        estado, espera = _estado_directo(texto)
        if estado is not None:
            await asyncio.sleep(espera)
            _, texto_despues = await _descargar(session, url, headers)
            estado_despues, _ = _estado_directo(texto_despues)
            if estado_despues == estado:
                raise Fallo("congelado", f"La emisión no avanza (igual tras {espera:.0f} segundos)")
        return

    # Vídeo directo (no .m3u8): si el "content-type" es una página web
    # normal en vez de vídeo/audio, es casi seguro un error disfrazado.
    content_type = (content_type or "").lower()
    if "text/html" in content_type or "application/json" in content_type:
        raise Fallo("vacio", "Devuelve una página web en vez de vídeo")
    if len(texto or "") <= 32:
        raise Fallo("vacio", "No devuelve vídeo")


async def check_one(session, sem, channel):
    """Devuelve None si el canal responde bien Y de verdad sirve
    contenido real (no una página de error, ni una lista vacía
    disfrazada de "200 OK", ni un directo congelado), o el Fallo con el
    motivo si no. Se hace un segundo intento (con una pequeña espera)
    antes de dar un canal por caído de verdad, por si ha sido solo un
    corte momentáneo."""
    url = (channel.get("stream_url") or "").strip()
    if not url.startswith("http"):
        return Fallo("vacio", "El enlace no empieza por http:// o https://")

    headers = {"User-Agent": PLAYER_USER_AGENT}

    async def attempt():
        try:
            await _comprobar_contenido(session, url, headers)
            return None
        except Fallo as f:
            return f
        except Exception as e:  # noqa: BLE001
            return Fallo("sin_respuesta", f"Fallo inesperado ({e.__class__.__name__})")

    async with sem:
        fallo = await attempt()
    if fallo is None:
        return None

    await asyncio.sleep(SEGUNDA_ESPERA_SEGUNDOS)

    async with sem:
        return await attempt()


async def _en_tandas(session, sem, metodo, ids, payload=None):
    """Hace la misma operación sobre muchos canales con pocas peticiones
    (TANDA_ACTUALIZACION ids en cada una) en vez de una por canal: con
    miles de canales, antes eran miles de peticiones a Supabase en cada
    comprobación."""
    headers = {**_headers(), "Content-Type": "application/json", "Prefer": "return=minimal"}

    async def una_tanda(tanda):
        url = f"{SUPABASE_URL}/rest/v1/bt_channels?id=in.({','.join(str(i) for i in tanda)})"
        async with sem:
            async with session.request(metodo, url, headers=headers, json=payload) as resp:
                if resp.status not in (200, 204):
                    body = await resp.text()
                    print(f"  ! Fallo al actualizar {len(tanda)} canales: HTTP {resp.status} — {body}")

    tandas = [ids[i:i + TANDA_ACTUALIZACION] for i in range(0, len(ids), TANDA_ACTUALIZACION)]
    await asyncio.gather(*(una_tanda(t) for t in tandas))


async def delete_channels(session, sem, ids, motivo):
    """Borra canales PARA SIEMPRE de la base de datos — no los oculta,
    los elimina de verdad, sin dejar rastro."""
    if not ids:
        return
    await _en_tandas(session, sem, "DELETE", list(ids))
    print(f"Borrados para siempre ({motivo}): {len(ids)}")


async def mark_checked(session, sem, ids):
    """A los canales que SÍ funcionan se les anota la fecha de la
    última comprobación, para el aviso del panel de administración (y si
    estaban marcados como caídos, vuelven a aparecer en la app)."""
    if not ids:
        return
    await _en_tandas(session, sem, "PATCH", list(ids), {"is_broken": False, "last_checked_at": _now_iso()})


async def mark_broken(session, sem, ids):
    """Marca canales como caídos (la app deja de mostrarlos) SIN borrarlos.
    No se toca "last_checked_at": así sigue guardando la última vez que
    el canal funcionó, y se sabe cuántos días lleva caído."""
    if not ids:
        return
    await _en_tandas(session, sem, "PATCH", list(ids), {"is_broken": True})
    print(f"Marcados como caídos (ocultos en la app, no borrados): {len(ids)}")


async def guardar_informe(session, resumen, eventos):
    """Guarda el resultado de esta comprobación para el "Centro de control"
    del panel (tablas bt_health_runs y bt_health_events). Si las tablas aún
    no existen (falta ejecutar el SQL), se avisa y se sigue: el informe es
    un extra, no debe impedir que se limpien los canales."""
    headers = {**_headers(), "Content-Type": "application/json", "Prefer": "return=representation"}
    async with session.post(f"{SUPABASE_URL}/rest/v1/bt_health_runs", headers=headers, json=resumen) as resp:
        if resp.status not in (200, 201):
            print(f"(No se ha guardado el informe para el panel: HTTP {resp.status}. ¿Falta ejecutar el SQL?)")
            return
        run_id = (await resp.json())[0]["id"]
    # Borra los informes de hace más de 30 días (función del SQL).
    async with session.post(f"{SUPABASE_URL}/rest/v1/rpc/bt_health_cleanup", headers=_headers(), json={}):
        pass
    headers["Prefer"] = "return=minimal"
    filas = [{**e, "run_id": run_id} for e in eventos]
    for i in range(0, len(filas), 500):
        async with session.post(
            f"{SUPABASE_URL}/rest/v1/bt_health_events", headers=headers, json=filas[i:i + 500]
        ) as resp:
            if resp.status not in (200, 201):
                print(f"(No se han guardado todos los detalles del informe: HTTP {resp.status})")
                return


def _evento(channel, tipo, detalle):
    return {
        "channel_id": str(channel["id"]),
        "channel_name": channel.get("name"),
        "category": channel.get("category"),
        "event": tipo,
        "detail": detalle,
    }


def _lleva_caido_demasiado(channel, ahora):
    """True si el canal no ha funcionado ni una sola vez en los últimos
    DIAS_ANTES_DE_BORRAR días. Si nunca se ha comprobado con éxito (sin
    fecha), no se borra: se queda oculto y el admin decide."""
    valor = channel.get("last_checked_at")
    if not valor:
        return False
    try:
        ultima_vez_ok = datetime.datetime.fromisoformat(valor.replace("Z", "+00:00"))
    except ValueError:
        return False
    if ultima_vez_ok.tzinfo is None:
        ultima_vez_ok = ultima_vez_ok.replace(tzinfo=datetime.timezone.utc)
    return ahora - ultima_vez_ok > datetime.timedelta(days=DIAS_ANTES_DE_BORRAR)


async def main():
    if not SUPABASE_SERVICE_ROLE_KEY:
        print(
            "Falta la variable SUPABASE_SERVICE_ROLE_KEY. Sin ella (guardada como "
            "secreto del repositorio en GitHub), este script no tiene permiso real "
            "para borrar ni actualizar nada en Supabase — solo podría leer, y ni eso "
            "si la lectura también está protegida."
        )
        sys.exit(1)

    inicio = _now_iso()
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
        por_id = {c["id"]: c for c in channels}
        eventos = [
            _evento(por_id[i], "duplicado", "Mismo enlace que otro canal: borrado") for i in duplicate_ids
        ]
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

        fallos = {ch["id"]: f for ch, f in zip(channels, results) if f is not None}
        dead = [ch for ch in channels if ch["id"] in fallos]
        alive = [ch for ch in channels if ch["id"] not in fallos]
        alive_ids = [ch["id"] for ch in alive]

        # Freno de seguridad: si falla una parte enorme de golpe, el
        # problema casi seguro no son los canales. No se toca nada.
        if len(channels) >= MINIMO_CANALES_PARA_FRENO:
            porcentaje = 100 * len(dead) / len(channels)
            if porcentaje > PORCENTAJE_MAXIMO_CAIDOS:
                await guardar_informe(session, {
                    "started_at": inicio, "finished_at": _now_iso(), "total": len(channels),
                    "ok": len(alive_ids), "broken": len(dead), "aborted": True,
                    "note": f"Freno de seguridad: fallaban {porcentaje:.0f} % de los canales, no se ha tocado nada",
                }, [])
                print(
                    f"\nFRENO DE SEGURIDAD: han fallado {len(dead)} de {len(channels)} canales "
                    f"({porcentaje:.0f} %), más del {PORCENTAJE_MAXIMO_CAIDOS} %. Seguramente es un "
                    "problema de red del servidor y no de los canales, así que NO se ha marcado "
                    "ni borrado ninguno. Vuelve a lanzarlo más tarde desde la pestaña Actions."
                )
                sys.exit(1)

        ahora = datetime.datetime.now(datetime.timezone.utc)
        to_delete = [ch for ch in dead if _lleva_caido_demasiado(ch, ahora)]
        to_delete_ids = {ch["id"] for ch in to_delete}
        to_flag = [ch for ch in dead if ch["id"] not in to_delete_ids and not ch.get("is_broken")]

        for ch in to_flag:
            f = fallos[ch["id"]]
            eventos.append(_evento(ch, f.tipo, f"{f.detalle}: ocultado en la app"))
        for ch in to_delete:
            f = fallos[ch["id"]]
            eventos.append(_evento(ch, "borrado", f"{f.detalle}; más de {DIAS_ANTES_DE_BORRAR} días caído: borrado"))
        for ch in alive:
            if ch.get("is_broken"):
                eventos.append(_evento(ch, "recuperado", "Vuelve a funcionar: visible otra vez en la app"))

        if dead:
            print(f"\nCanales que no responden hoy: {len(dead)}")
            for ch in dead:
                print(f"  - {ch.get('name')} ({ch['id']}): {fallos[ch['id']].detalle}")
            await mark_broken(session, sem, [ch["id"] for ch in to_flag])
            await delete_channels(
                session, sem, list(to_delete_ids), f"más de {DIAS_ANTES_DE_BORRAR} días caídos"
            )
        else:
            print("\nNo hay canales caídos ahora mismo.")

        # 3) A los que sí funcionan se les anota cuándo se comprobaron
        # (y si estaban marcados como caídos, vuelven a aparecer en la app).
        await mark_checked(session, sem, alive_ids)

        await guardar_informe(session, {
            "started_at": inicio, "finished_at": _now_iso(), "total": len(channels) + len(duplicate_ids),
            "ok": len(alive_ids), "broken": len(dead), "hidden": len(to_flag),
            "deleted": len(to_delete_ids) + len(duplicate_ids),
            "restored": sum(1 for ch in alive if ch.get("is_broken")), "aborted": False,
        }, eventos)

        print("\nResumen final:")
        print(f"  Duplicados borrados: {len(duplicate_ids)}")
        print(f"  Caídos hoy (ocultos en la app): {len(dead)}")
        print(f"  Borrados por llevar más de {DIAS_ANTES_DE_BORRAR} días caídos: {len(to_delete_ids)}")
        print(f"  Funcionando de verdad ahora mismo: {len(alive_ids)}")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except Exception as e:
        print(f"Fallo al comprobar los canales: {e}")
        sys.exit(1)
