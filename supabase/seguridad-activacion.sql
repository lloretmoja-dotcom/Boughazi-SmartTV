-- =====================================================================
-- Boughazi-TV — Seguridad del código de activación (PROPUESTA)
-- =====================================================================
--
-- ESTE ARCHIVO NO SE EJECUTA SOLO. Hay que revisarlo y lanzarlo a mano en
-- Supabase → SQL Editor, por partes y en orden.
--
-- Qué arregla:
--   Hasta ahora la propia app hacía la activación en dos pasos: marcaba
--   el código como usado y luego escribía "linked_code" en la fila del
--   usuario (bt_viewers). Como lo hace la app con la clave pública,
--   cualquiera con una cuenta podía hacer el segundo paso a mano (con
--   curl, por ejemplo) y "activarse" sin gastar ningún código.
--
--   Con esto:
--   1) La activación la hace una única función dentro de la base de datos
--      (bt_redeem_code), que reclama el código y vincula la cuenta a la
--      vez: o las dos cosas, o ninguna. La app ya la usa en cuanto exista.
--   2) Los usuarios normales dejan de poder escribir en bt_access_codes y
--      en la columna linked_code de bt_viewers.
--   3) Solo pueden leer canales las cuentas con un código activo (o el
--      admin). Si desactivas o borras un código, esa cuenta deja de ver
--      canales aunque tenga la app abierta.
--
-- Supuestos (compruébalos antes): las columnas son las que usa la app:
--   bt_viewers(id = auth.uid(), linked_code, linked_at)
--   bt_access_codes(id, code, label, used_by_email, used_at, active, created_at)
--   bt_channels(..., is_broken, last_checked_at)
--   y ya existe la función bt_is_admin() que usa el panel.
-- =====================================================================


-- ---------------------------------------------------------------------
-- PASO 0. Ver qué reglas (policies) hay ahora mismo. Ejecuta esto solo y
-- guarda el resultado: hace falta para el paso 3.
-- ---------------------------------------------------------------------
select tablename, policyname, cmd, roles, qual, with_check
from pg_policies
where schemaname = 'public'
  and tablename in ('bt_viewers', 'bt_access_codes', 'bt_channels', 'bt_presence')
order by tablename, cmd;


-- ---------------------------------------------------------------------
-- PASO 1. Función segura de activación (la app la llama al activar).
-- Devuelve true si el código era válido y se ha vinculado, false si no.
-- ---------------------------------------------------------------------
create or replace function public.bt_redeem_code(p_code text)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid   uuid := auth.uid();
  v_email text;
  v_code  text := upper(trim(p_code));
  v_id    bt_access_codes.id%type;
begin
  if v_uid is null then
    raise exception 'Hace falta iniciar sesión' using errcode = '28000';
  end if;

  select email into v_email from auth.users where id = v_uid;

  -- Reclamar el código (solo si está libre y activo). "for update" evita
  -- que dos personas reclamen el mismo código a la vez.
  select id into v_id
  from bt_access_codes
  where code = v_code and used_by_email is null and active = true
  for update;

  if v_id is null then
    return false;
  end if;

  update bt_access_codes
  set used_by_email = v_email, used_at = now()
  where id = v_id;

  update bt_viewers
  set linked_code = v_code, linked_at = now()
  where id = v_uid;

  if not found then
    -- La cuenta no tiene fila en bt_viewers: se deshace todo (el código
    -- vuelve a quedar libre) para no gastarlo en balde.
    raise exception 'Esta cuenta no tiene ficha de espectador (bt_viewers)';
  end if;

  return true;
end;
$$;

revoke all on function public.bt_redeem_code(text) from public, anon;
grant execute on function public.bt_redeem_code(text) to authenticated;


-- ---------------------------------------------------------------------
-- PASO 2. ¿Tiene esta cuenta un código activo? (para las reglas)
-- ---------------------------------------------------------------------
create or replace function public.bt_has_access()
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select exists (
    select 1
    from bt_viewers v
    join bt_access_codes c on c.code = v.linked_code
    where v.id = auth.uid()
      and c.active = true
  );
$$;

revoke all on function public.bt_has_access() from public, anon;
grant execute on function public.bt_has_access() to authenticated;


-- ---------------------------------------------------------------------
-- PASO 3. Quitar las reglas antiguas que dejan escribir o leer de más.
--
-- Las reglas de Postgres se SUMAN: si queda una regla antigua que deja
-- leer bt_channels a cualquier usuario, la nueva no sirve de nada. Con el
-- resultado del PASO 0, borra las reglas para usuarios normales de:
--   - bt_channels: cualquier SELECT que no compruebe el código
--   - bt_access_codes: cualquier SELECT/UPDATE para usuarios normales
--   - bt_viewers: cualquier UPDATE para usuarios normales
-- Deja las del admin (las que usan bt_is_admin()).
--
-- Ejemplo (cambia "nombre_de_la_regla" por el nombre real):
--   drop policy "nombre_de_la_regla" on public.bt_channels;
-- ---------------------------------------------------------------------


-- ---------------------------------------------------------------------
-- PASO 4. Reglas nuevas.
-- ---------------------------------------------------------------------
alter table public.bt_channels     enable row level security;
alter table public.bt_access_codes enable row level security;
alter table public.bt_viewers      enable row level security;

-- Canales: solo los ven las cuentas con código activo, y el admin.
drop policy if exists "bt_channels_lectura_con_codigo" on public.bt_channels;
create policy "bt_channels_lectura_con_codigo"
  on public.bt_channels for select
  to authenticated
  using (public.bt_has_access() or public.bt_is_admin());

-- Códigos: solo el admin (la app ya no los toca directamente; usa
-- bt_redeem_code). Cubre leer, crear, cambiar y borrar.
drop policy if exists "bt_access_codes_solo_admin" on public.bt_access_codes;
create policy "bt_access_codes_solo_admin"
  on public.bt_access_codes for all
  to authenticated
  using (public.bt_is_admin())
  with check (public.bt_is_admin());

-- Espectadores: cada uno puede LEER su propia fila (la app la usa para
-- saber si ya tiene código). Escribir linked_code solo se puede a través
-- de bt_redeem_code.
drop policy if exists "bt_viewers_leer_la_suya" on public.bt_viewers;
create policy "bt_viewers_leer_la_suya"
  on public.bt_viewers for select
  to authenticated
  using (id = auth.uid() or public.bt_is_admin());

-- Por si había permisos de escritura a nivel de columna:
revoke update (linked_code, linked_at) on public.bt_viewers from authenticated, anon;


-- ---------------------------------------------------------------------
-- PASO 5. Comprobar. Con una cuenta normal SIN código, desde la app o con
-- curl y su token, esto tiene que devolver una lista vacía:
--   GET /rest/v1/bt_channels?select=id&limit=1
-- y esto tiene que fallar o no cambiar ninguna fila:
--   PATCH /rest/v1/bt_viewers?id=eq.<su id>   {"linked_code":"X"}
-- ---------------------------------------------------------------------
