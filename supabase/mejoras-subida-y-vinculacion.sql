-- =====================================================================
-- Boughazi-TV — Velocidad, vinculación por código y listas automáticas
-- =====================================================================
--
-- ESTE ARCHIVO NO SE EJECUTA SOLO. Cópialo entero en Supabase → SQL
-- Editor y pulsa "Run" una vez. Se puede volver a ejecutar sin miedo:
-- no borra datos ni canales, solo crea lo que falte.
--
-- Qué añade:
--   1) Índices para que las consultas de la app y del panel sean rápidas
--      aunque haya miles de canales.
--   2) Un "número de versión" del catálogo de canales. Sube solo cada vez
--      que se añade, cambia o borra un canal. Las teles preguntan solo ese
--      número (una respuesta diminuta) y descargan la lista entera
--      únicamente cuando ha cambiado. Hace el papel de caché: sin esto,
--      cada tele descargaba miles de canales cada 5 minutos.
--   3) Vinculación por código (Web Pairing): la tele muestra un código, el
--      administrador lo escribe en el panel junto con una lista (enlace M3U
--      o Xtream Codes) y la tele la recibe sola en unos segundos.
--   4) Listas automáticas: enlaces que el script de cada noche vuelve a
--      importar solo, añadiendo los canales nuevos.
--   5) Informes de la vigilancia automática de canales (cada hora), que se
--      ven en el "Centro de control" del panel.
--
-- Supone que ya existen las tablas bt_channels, bt_presence y la función
-- bt_is_admin() que usa el panel.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. Índices
-- ---------------------------------------------------------------------
-- Lista de la app: canales que funcionan, ordenados por número.
create index if not exists bt_channels_activos_por_numero
  on public.bt_channels (channel_number) where is_broken = false;
-- Panel: agrupar por país/categoría.
create index if not exists bt_channels_por_categoria
  on public.bt_channels (category);
-- Importar sin duplicados y comprobación nocturna (buscar por enlace).
-- Índice "hash": algunos enlaces son tan largos (miles de letras) que no
-- caben en un índice normal y Supabase daba el error "index row size
-- exceeds btree maximum". El índice hash guarda solo una huella del
-- enlace, así que admite enlaces de cualquier tamaño.
drop index if exists public.bt_channels_por_enlace;
create index if not exists bt_channels_por_enlace_hash
  on public.bt_channels using hash (stream_url);
-- "Última comprobación automática" del panel.
create index if not exists bt_channels_ultima_comprobacion
  on public.bt_channels (last_checked_at desc nulls last);
-- "Viendo ahora" del panel.
create index if not exists bt_presence_ultimo_aviso
  on public.bt_presence (last_ping);


-- ---------------------------------------------------------------------
-- 2. Versión del catálogo (caché de las teles)
-- ---------------------------------------------------------------------
create table if not exists public.bt_catalog_state (
  id         int primary key default 1 check (id = 1),
  version    bigint not null default 1,
  updated_at timestamptz not null default now()
);
insert into public.bt_catalog_state (id) values (1) on conflict (id) do nothing;
alter table public.bt_catalog_state enable row level security;

-- Una sola subida por operación (aunque se importen 5000 canales de golpe).
create or replace function public.bt_bump_catalog_version()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  update bt_catalog_state set version = version + 1, updated_at = now() where id = 1;
  return null;
end;
$$;

drop trigger if exists bt_channels_sube_version on public.bt_channels;
create trigger bt_channels_sube_version
  after insert or update or delete or truncate on public.bt_channels
  for each statement execute function public.bt_bump_catalog_version();

-- ---------------------------------------------------------------------
-- 3. Vinculación por código (Web Pairing)
-- ---------------------------------------------------------------------
create table if not exists public.bt_pairings (
  code            text primary key,
  viewer_id       uuid not null references auth.users (id) on delete cascade,
  created_at      timestamptz not null default now(),
  -- Si nadie lo usa, el código deja de valer a los 30 minutos.
  expires_at      timestamptz not null default now() + interval '30 minutes',
  label           text,
  -- 'm3u' (enlace de lista) o 'xtream' (servidor + usuario + contraseña).
  -- Mientras sea null, la tele sigue esperando.
  playlist_type   text check (playlist_type in ('m3u', 'xtream')),
  playlist_url    text,
  xtream_server   text,
  xtream_username text,
  xtream_password text,
  updated_at      timestamptz not null default now()
);
create index if not exists bt_pairings_por_tele on public.bt_pairings (viewer_id);
alter table public.bt_pairings enable row level security;

-- Solo el administrador puede ver y cambiar códigos desde el panel. Las
-- teles no tocan la tabla directamente: usan las dos funciones de abajo.
drop policy if exists "bt_pairings_solo_admin" on public.bt_pairings;
create policy "bt_pairings_solo_admin" on public.bt_pairings
  for all to authenticated
  using (public.bt_is_admin())
  with check (public.bt_is_admin());

-- La tele pide un código nuevo. Devuelve un código de 8 letras/números
-- (sin 0/O ni 1/I para que no se confundan). Si la tele ya tenía un código
-- sin usar y sin caducar, devuelve ese mismo.
create or replace function public.bt_pairing_start()
returns text
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid   uuid := auth.uid();
  v_chars text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  v_code  text;
  v_bytes bytea;
begin
  if v_uid is null then
    raise exception 'Hace falta iniciar sesión' using errcode = '28000';
  end if;

  select code into v_code from bt_pairings
  where viewer_id = v_uid and playlist_type is null and expires_at > now()
  order by created_at desc limit 1;
  if v_code is not null then
    return v_code;
  end if;

  -- Limpia los códigos viejos de esta tele que nunca se usaron.
  delete from bt_pairings
  where viewer_id = v_uid and playlist_type is null;

  loop
    v_bytes := uuid_send(gen_random_uuid());
    v_code := '';
    for i in 0..7 loop
      v_code := v_code || substr(v_chars, (get_byte(v_bytes, i) % 32) + 1, 1);
    end loop;
    begin
      insert into bt_pairings (code, viewer_id) values (v_code, v_uid);
      return v_code;
    exception when unique_violation then
      -- Casi imposible, pero si el código ya existía se prueba otro.
    end;
  end loop;
end;
$$;

-- La tele pregunta si ya le han enviado una lista. Devuelve null mientras
-- no haya nada. Solo puede ver la suya.
create or replace function public.bt_pairing_get()
returns json
language sql
stable
security definer
set search_path = public
as $$
  select json_build_object(
    'code', code,
    'label', label,
    'playlist_type', playlist_type,
    'playlist_url', playlist_url,
    'xtream_server', xtream_server,
    'xtream_username', xtream_username,
    'xtream_password', xtream_password,
    'updated_at', updated_at
  )
  from bt_pairings
  where viewer_id = auth.uid() and playlist_type is not null
  order by updated_at desc
  limit 1;
$$;

-- La tele quita su lista vinculada y vuelve a la lista oficial.
create or replace function public.bt_pairing_unlink()
returns void
language sql
security definer
set search_path = public
as $$
  delete from bt_pairings where viewer_id = auth.uid();
$$;

-- El panel envía una lista a un código. Solo el administrador. Devuelve
-- false si el código no existe o ya caducó sin usarse.
create or replace function public.bt_pairing_send(
  p_code text,
  p_playlist_type text,
  p_playlist_url text default null,
  p_xtream_server text default null,
  p_xtream_username text default null,
  p_xtream_password text default null,
  p_label text default null
)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
  v_found int;
begin
  if not public.bt_is_admin() then
    raise exception 'Solo el administrador puede enviar listas' using errcode = '42501';
  end if;
  if p_playlist_type not in ('m3u', 'xtream') then
    raise exception 'Tipo de lista no válido';
  end if;

  update bt_pairings set
    playlist_type   = p_playlist_type,
    playlist_url    = p_playlist_url,
    xtream_server   = p_xtream_server,
    xtream_username = p_xtream_username,
    xtream_password = p_xtream_password,
    label           = coalesce(p_label, label),
    updated_at      = now()
  where code = upper(trim(p_code))
    -- Un código ya usado se puede volver a usar para cambiar la lista;
    -- uno sin usar solo vale mientras no haya caducado.
    and (playlist_type is not null or expires_at > now());
  get diagnostics v_found = row_count;
  return v_found > 0;
end;
$$;

-- Lo que preguntan las teles cada minuto. Si la tele tiene una lista
-- vinculada por código, también devuelve cuándo se cambió por última vez.
create or replace function public.bt_catalog_version()
returns json
language sql
stable
security definer
set search_path = public
as $$
  select json_build_object(
    'version', (select version from bt_catalog_state where id = 1),
    'pairing_updated_at', (select updated_at from bt_pairings
                           where viewer_id = auth.uid() and playlist_type is not null
                           order by updated_at desc limit 1)
  );
$$;


revoke all on function public.bt_pairing_start() from public, anon;
revoke all on function public.bt_pairing_get() from public, anon;
revoke all on function public.bt_pairing_unlink() from public, anon;
revoke all on function public.bt_pairing_send(text, text, text, text, text, text, text) from public, anon;
revoke all on function public.bt_catalog_version() from public, anon;
grant execute on function public.bt_pairing_start() to authenticated;
grant execute on function public.bt_pairing_get() to authenticated;
grant execute on function public.bt_pairing_unlink() to authenticated;
grant execute on function public.bt_pairing_send(text, text, text, text, text, text, text) to authenticated;
grant execute on function public.bt_catalog_version() to authenticated;


-- ---------------------------------------------------------------------
-- 4. Listas automáticas (se vuelven a importar cada noche)
-- ---------------------------------------------------------------------
create table if not exists public.bt_auto_sources (
  id                bigint generated always as identity primary key,
  name              text,
  kind              text not null check (kind in ('m3u', 'xtream')),
  url               text,
  xtream_server     text,
  xtream_username   text,
  xtream_password   text,
  -- Si se rellena, todos los canales de esta lista entran con esta
  -- categoría (por ejemplo, el país).
  category_override text,
  enabled           boolean not null default true,
  last_run_at       timestamptz,
  last_result       text,
  created_at        timestamptz not null default now()
);
alter table public.bt_auto_sources enable row level security;

drop policy if exists "bt_auto_sources_solo_admin" on public.bt_auto_sources;
create policy "bt_auto_sources_solo_admin" on public.bt_auto_sources
  for all to authenticated
  using (public.bt_is_admin())
  with check (public.bt_is_admin());


-- ---------------------------------------------------------------------
-- 5. Informes de la vigilancia automática de canales ("Centro de control")
-- ---------------------------------------------------------------------
-- Cada comprobación (cada hora) guarda un resumen en bt_health_runs y, en
-- bt_health_events, qué ha hecho con cada canal que ha cambiado: ocultado
-- (y por qué: error 404/500, no contesta, bucle, vacío, congelado),
-- borrado, recuperado o duplicado. Solo el administrador puede leerlos;
-- los escribe el script con la clave secreta.
create table if not exists public.bt_health_runs (
  id          bigint generated always as identity primary key,
  started_at  timestamptz not null,
  finished_at timestamptz,
  total       int not null default 0,
  ok          int not null default 0,
  broken      int not null default 0,
  hidden      int not null default 0,
  deleted     int not null default 0,
  restored    int not null default 0,
  aborted     boolean not null default false,
  note        text
);
create index if not exists bt_health_runs_recientes on public.bt_health_runs (started_at desc);

create table if not exists public.bt_health_events (
  id           bigint generated always as identity primary key,
  run_id       bigint not null references public.bt_health_runs (id) on delete cascade,
  created_at   timestamptz not null default now(),
  channel_id   text,
  channel_name text,
  category     text,
  event        text not null,
  detail       text
);
create index if not exists bt_health_events_por_run on public.bt_health_events (run_id);
create index if not exists bt_health_events_recientes on public.bt_health_events (created_at desc);

alter table public.bt_health_runs enable row level security;
alter table public.bt_health_events enable row level security;

drop policy if exists "bt_health_runs_admin_lee" on public.bt_health_runs;
create policy "bt_health_runs_admin_lee" on public.bt_health_runs
  for select to authenticated using (public.bt_is_admin());
drop policy if exists "bt_health_events_admin_lee" on public.bt_health_events;
create policy "bt_health_events_admin_lee" on public.bt_health_events
  for select to authenticated using (public.bt_is_admin());

-- Los informes de más de 30 días se borran solos en cada comprobación
-- (el script llama a esta función), para que la tabla no crezca sin fin.
create or replace function public.bt_health_cleanup()
returns void
language sql
security definer
set search_path = public
as $$
  delete from bt_health_runs where started_at < now() - interval '30 days';
$$;
revoke all on function public.bt_health_cleanup() from public, anon, authenticated;
