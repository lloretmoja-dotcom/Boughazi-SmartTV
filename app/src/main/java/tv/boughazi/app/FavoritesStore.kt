package tv.boughazi.app

import android.content.Context

/**
 * Guarda en el propio dispositivo los canales favoritos del usuario.
 * Se añaden o se quitan manteniendo pulsado OK sobre un canal de la lista.
 */
class FavoritesStore(context: Context) {
    private val prefs =
        context.getSharedPreferences("boughazi_tv_favorites", Context.MODE_PRIVATE)

    fun ids(): Set<String> = prefs.getStringSet("ids", emptySet()) ?: emptySet()

    fun isFavorite(id: String): Boolean = ids().contains(id)

    /** Cambia el estado del canal y devuelve true si ahora es favorito. */
    fun toggle(id: String): Boolean {
        val current = HashSet(ids())
        val nowFavorite = if (current.contains(id)) {
            current.remove(id)
            false
        } else {
            current.add(id)
            true
        }
        prefs.edit().putStringSet("ids", current).apply()
        return nowFavorite
    }
}
