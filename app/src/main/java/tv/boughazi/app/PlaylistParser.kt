package tv.boughazi.app

import java.net.URLEncoder

/**
 * Lee listas de canales que vienen de fuera (enlace M3U o servidor
 * Xtream Codes) y las convierte en canales de la app.
 *
 * Aquí no hay nada de Android ni de internet: solo texto que entra y
 * canales que salen. Así se puede probar con tests normales, sin
 * tele ni conexión.
 */
object PlaylistParser {

    /** Un canal tal como viene en la lista, antes de convertirlo en [Channel]. */
    data class Entry(
        val name: String,
        val group: String?,
        val logo: String?,
        val url: String
    )

    // Atributos de la línea #EXTINF, por ejemplo: tvg-logo="http://..."
    private val attributeRegex = Regex("([A-Za-z0-9_-]+)\\s*=\\s*\"([^\"]*)\"")

    /**
     * Lee una lista M3U. Cada canal suele venir en dos líneas:
     *
     *   #EXTINF:-1 tvg-logo="http://logo.png" group-title="España",La 1
     *   http://servidor/la1.m3u8
     *
     * El nombre es lo que va después de la primera coma que NO está
     * dentro de comillas (hay grupos con comas dentro, como
     * group-title="Noticias, Deportes"). También se aceptan enlaces
     * sueltos sin línea #EXTINF delante. Solo se aceptan enlaces que
     * empiezan por http:// o https://.
     */
    fun parseM3u(raw: String): List<Entry> {
        // Algunos programas guardan el archivo con una "marca" invisible
        // al principio (BOM) que estropearía la primera línea.
        val text = raw.removePrefix("\uFEFF")
        val result = mutableListOf<Entry>()
        var pendingInfo: String? = null // la línea #EXTINF que espera su enlace
        var pendingGroup: String? = null // grupo indicado con #EXTGRP

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim().removePrefix("\uFEFF")
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                pendingInfo = line
                pendingGroup = null
                continue
            }
            if (line.startsWith("#EXTGRP:", ignoreCase = true)) {
                pendingGroup = line.substringAfter(':').trim().ifEmpty { null }
                continue
            }
            // Cualquier otra línea que empieza por # (#EXTM3U, #EXTVLCOPT...)
            // no es un canal: se salta sin perder el #EXTINF pendiente.
            if (line.startsWith("#")) continue

            if (isHttpUrl(line)) {
                val info = pendingInfo
                val attributes = if (info != null) parseAttributes(info) else emptyMap()
                val name = info?.let { nameAfterComma(it) }?.takeIf { it.isNotBlank() }
                    ?: attributes["tvg-name"]?.trim()?.takeIf { it.isNotBlank() }
                    ?: ""
                val group = attributes["group-title"]?.trim()?.takeIf { it.isNotBlank() }
                    ?: pendingGroup
                val logo = attributes["tvg-logo"]?.trim()?.takeIf { isHttpUrl(it) }
                result.add(Entry(name = name, group = group, logo = logo, url = line))
            }
            // Haya servido o no (enlace que no es http/https), el #EXTINF
            // ya se ha usado: no debe pegarse al siguiente enlace.
            pendingInfo = null
            pendingGroup = null
        }
        return result
    }

    /**
     * Convierte los canales leídos en canales de la app. Se numeran
     * seguidos (1, 2, 3...) en el mismo orden que la lista.
     */
    fun toChannels(entries: List<Entry>, idPrefix: String = "pair-"): List<Channel> {
        return entries
            .filter { isHttpUrl(it.url) }
            .mapIndexed { index, entry ->
                Channel(
                    id = "$idPrefix$index",
                    channelNumber = index + 1,
                    name = entry.name.trim().ifBlank { "Canal ${index + 1}" },
                    category = entry.group?.trim()?.takeIf { it.isNotBlank() } ?: "General",
                    logoUrl = entry.logo?.takeIf { isHttpUrl(it) },
                    streamUrl = entry.url.trim()
                )
            }
    }

    /** true solo para enlaces http:// o https:// (nada de rtmp, file, etc.). */
    fun isHttpUrl(value: String?): Boolean {
        if (value == null) return false
        val v = value.trim().lowercase()
        return (v.startsWith("http://") && v.length > "http://".length) ||
            (v.startsWith("https://") && v.length > "https://".length)
    }

    // ------------------------------------------------------------------
    // Xtream Codes
    // ------------------------------------------------------------------

    /**
     * Deja la dirección del servidor Xtream lista para usar: sin espacios,
     * sin barra al final y con http:// delante si no lo tenía (mucha
     * gente escribe solo "servidor.com:8080").
     */
    fun normalizeXtreamServer(server: String): String {
        var s = server.trim().trimEnd('/')
        if (!s.lowercase().startsWith("http://") && !s.lowercase().startsWith("https://")) {
            s = "http://$s"
        }
        return s
    }

    /** Dirección de la API de Xtream para una acción (get_live_streams...). */
    fun xtreamApiUrl(server: String, username: String, password: String, action: String): String {
        return "${normalizeXtreamServer(server)}/player_api.php" +
            "?username=${encodeQuery(username)}" +
            "&password=${encodeQuery(password)}" +
            "&action=${encodeQuery(action)}"
    }

    /** Enlace de vídeo de un canal en directo de Xtream. */
    fun xtreamStreamUrl(server: String, username: String, password: String, streamId: String): String {
        return "${normalizeXtreamServer(server)}/live/" +
            "${encodePath(username)}/${encodePath(password)}/${encodePath(streamId)}.m3u8"
    }

    // ------------------------------------------------------------------
    // Ayudas internas
    // ------------------------------------------------------------------

    /** Nombre del canal: lo que va tras la primera coma fuera de comillas. */
    private fun nameAfterComma(info: String): String? {
        var inQuotes = false
        for (i in info.indices) {
            val c = info[i]
            if (c == '"') inQuotes = !inQuotes
            if (c == ',' && !inQuotes) return info.substring(i + 1).trim()
        }
        return null
    }

    /** Atributos (tvg-logo, group-title...) de la parte ANTES del nombre. */
    private fun parseAttributes(info: String): Map<String, String> {
        var inQuotes = false
        var end = info.length
        for (i in info.indices) {
            val c = info[i]
            if (c == '"') inQuotes = !inQuotes
            if (c == ',' && !inQuotes) {
                end = i
                break
            }
        }
        val head = info.substring(0, end)
        val map = mutableMapOf<String, String>()
        for (match in attributeRegex.findAll(head)) {
            map[match.groupValues[1].lowercase()] = match.groupValues[2]
        }
        return map
    }

    private fun encodeQuery(value: String): String = URLEncoder.encode(value, "UTF-8")

    // En una ruta los espacios van como %20, no como "+".
    private fun encodePath(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
