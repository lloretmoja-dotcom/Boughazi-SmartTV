-- =====================================================================
-- Robot de canales: historial de pasadas para el panel "Admin"
-- =====================================================================
-- Ejecútalo UNA vez en Supabase → SQL Editor → New query → Run.
-- Se puede volver a ejecutar sin problema (no borra nada).
--
-- El robot diario (scripts/massive_indexer.js) guarda aquí el resumen de
-- cada pasada con la clave secreta; el panel lo lee para mostrar la
-- última sincronización y los canales añadidos. Solo el administrador
-- puede leerlo (misma función bt_is_admin() que el resto del panel).
-- =====================================================================

create table if not exists public.bt_robot_runs (
  id           bigint generated always as identity primary key,
  started_at   timestamptz not null,
  finished_at  timestamptz not null default now(),
  status       text not null,            -- ÉXITO / ADVERTENCIA / ERROR
  analyzed     int not null default 0,   -- canales distintos encontrados
  tested       int not null default 0,   -- enlaces probados
  added        int not null default 0,   -- canales nuevos añadidos
  repaired     int not null default 0,   -- canales caídos arreglados con otro enlace
  no_signal    int not null default 0,   -- descartados por no tener señal
  total_in_db  int not null default 0,
  broken_in_db int not null default 0,
  sources      jsonb,
  by_country   jsonb,
  note         text
);
create index if not exists bt_robot_runs_recientes on public.bt_robot_runs (finished_at desc);

alter table public.bt_robot_runs enable row level security;

drop policy if exists "bt_robot_runs_admin_lee" on public.bt_robot_runs;
create policy "bt_robot_runs_admin_lee" on public.bt_robot_runs
  for select to authenticated using (public.bt_is_admin());
