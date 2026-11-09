package tv.boughazi.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lee las películas de Supabase (tabla "peliculas"). Solo salen las que
 * están marcadas como activas. Devuelve null si no hay internet o falla
 * la consulta, para poder avisar sin cerrar la app.
 */
object MovieRepository {

    suspend fun fetchAll(): List<Movie>? = withContext(Dispatchers.IO) {
        try {
            val url = URL(
                "${SupabaseConfig.URL}/rest/v1/peliculas" +
                    "?activa=eq.true&order=creada_en.desc&limit=300" +
                    "&select=id,titulo,descripcion,categoria,anio,poster_url,video_url"
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("apikey", SupabaseConfig.ANON_KEY)
            conn.setRequestProperty("Authorization", "Bearer ${SupabaseConfig.ANON_KEY}")
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            if (conn.responseCode !in 200..299) return@withContext null
            val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            val result = ArrayList<Movie>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val video = o.optString("video_url", "")
                if (video.isBlank()) continue
                result.add(
                    Movie(
                        id = o.getLong("id"),
                        title = o.optString("titulo", ""),
                        description = if (o.isNull("descripcion")) "" else o.getString("descripcion"),
                        category = if (o.isNull("categoria")) "" else o.getString("categoria"),
                        year = if (o.isNull("anio")) null else o.getInt("anio"),
                        posterUrl = if (o.isNull("poster_url")) null else o.getString("poster_url"),
                        videoUrl = video
                    )
                )
            }
            result
        } catch (e: Exception) {
            null
        }
    }
}
