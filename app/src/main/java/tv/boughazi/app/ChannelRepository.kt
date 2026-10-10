package tv.boughazi.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Igual que con el código de activación: si algo falla (por ejemplo
 * la sesión ha caducado) queremos saber el motivo REAL en vez de
 * quedarnos callados y decir "no hay canales" sin más.
 */
sealed class ChannelsResult {
    data class Success(val channels: List<Channel>, val totalReportedByServer: Int?) : ChannelsResult()
    data class Failure(val httpStatus: Int, val detail: String) : ChannelsResult()
}

/**
 * Descarga la lista de canales de Supabase. Se llama una sola vez
 * al entrar (o al tirar hacia abajo para refrescar) y luego todo
 * el "zapping" ocurre sobre la lista ya guardada en memoria — así
 * es instantáneo, como pide el estilo TiviMate.
 */
class ChannelRepository {

    suspend fun fetchChannels(session: UserSession): ChannelsResult = withContext(Dispatchers.IO) {
        // Supabase solo entrega 1000 filas como máximo en cada petición.
        // Como ya hay más de 1000 canales, los pedimos por partes (de
        // 1000 en 1000) hasta traerlos todos, para que no se corte la
        // lista al llegar al canal número 1000.
        val pageSize = 1000
        var offset = 0
        var page = 1
        var totalReportedByServer: Int? = null
        val allChannels = mutableListOf<Channel>()

        while (true) {
            // Todo este bloque (conexión, lectura Y análisis) va dentro de un
            // único try/catch. Antes, un fallo de red en la SEGUNDA tanda en
            // adelante (no en la primera) podía "escaparse" sin control y
            // dejar la lista a medias sin que nadie se enterara. Ahora,
            // cualquier fallo en cualquier tanda para todo el proceso y lo
            // dice claramente, con el número de tanda donde ha pasado.
            try {
                val url = URL(
                    "${SupabaseConfig.URL}/rest/v1/bt_channels" +
                        "?select=id,channel_number,name,category,logo_url,stream_url,is_broken" +
                        "&is_broken=not.is.true" +
                        "&order=channel_number.asc.nullslast"
                )
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("apikey", SupabaseConfig.ANON_KEY)
                conn.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
                // Igual que hace el panel de administración (que ya sabemos
                // que funciona bien con miles de canales): pedimos cada
                // tanda con las cabeceras "Range", para que Supabase nos
                // deje avanzar de verdad más allá de los primeros 1000.
                conn.setRequestProperty("Range-Unit", "items")
                conn.setRequestProperty("Range", "$offset-${offset + pageSize - 1}")
                // Pedimos el total real que tiene la base de datos, pero SOLO
                // en la primera tanda. Antes se pedía en todas las tandas, y
                // eso obligaba al servidor a contar TODOS los canales de la
                // base de datos una vez por cada tanda (muy lento si hay
                // miles de canales) en vez de solo una vez. Esta es la razón
                // principal de que la app tardara varios minutos en cargar.
                if (page == 1) {
                    conn.setRequestProperty("Prefer", "count=exact")
                }
                conn.connectTimeout = 15000
                conn.readTimeout = 15000

                val status = conn.responseCode
                val contentRange = conn.getHeaderField("Content-Range")
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""

                if (status !in 200..299) {
                    return@withContext ChannelsResult.Failure(
                        status,
                        "Tanda $page (canales $offset en adelante): ${describeError(text)}"
                    )
                }

                if (page == 1 && contentRange != null) {
                    // La cabecera tiene forma "0-999/4041". Nos quedamos con
                    // el número de después de la barra.
                    totalReportedByServer = contentRange.substringAfter("/", "").toIntOrNull()
                }

                val arr = JSONArray(text)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    allChannels.add(
                        Channel(
                            id = o.getString("id"),
                            channelNumber = if (o.isNull("channel_number")) null else o.getInt("channel_number"),
                            name = o.optString("name", ""),
                            category = o.optString("category", "General"),
                            logoUrl = if (o.isNull("logo_url")) null else o.getString("logo_url"),
                            streamUrl = o.optString("stream_url", "")
                        )
                    )
                }

                if (arr.length() < pageSize) break
                offset += pageSize
                page += 1
            } catch (e: Exception) {
                return@withContext ChannelsResult.Failure(
                    -1,
                    "Fallo de conexión en la tanda $page (canales $offset en adelante): ${e.javaClass.simpleName} — ${e.message}"
                )
            }
        }

        ChannelsResult.Success(allChannels, totalReportedByServer)
    }

    private fun describeError(rawBody: String): String {
        return try {
            val obj = JSONObject(rawBody)
            obj.optString("message", obj.optString("msg", rawBody)).ifBlank { rawBody }
        } catch (e: Exception) {
            rawBody
        }
    }
}
