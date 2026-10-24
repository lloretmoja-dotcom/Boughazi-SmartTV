package tv.boughazi.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Una lista enviada a esta tele desde el panel de administración con
 * la vinculación por código (Web Pairing). Puede ser un enlace M3U o
 * un servidor Xtream Codes (servidor + usuario + contraseña).
 */
data class PairedPlaylist(
    val code: String?,
    val label: String?,
    val type: String,
    val playlistUrl: String?,
    val xtreamServer: String?,
    val xtreamUsername: String?,
    val xtreamPassword: String?,
    val updatedAt: String?
)

/**
 * "Número de versión" del catálogo. Las teles preguntan solo esto cada
 * minuto (una respuesta diminuta) y descargan la lista entera de canales
 * únicamente cuando ha cambiado. NotAvailable = el SQL nuevo todavía no
 * está ejecutado en Supabase.
 */
sealed class CatalogVersionResult {
    data class Success(val version: Long, val pairingUpdatedAt: String?) : CatalogVersionResult()
    object NotAvailable : CatalogVersionResult()
    data class Failure(val httpStatus: Int, val detail: String) : CatalogVersionResult()
}

sealed class PairingStartResult {
    data class Success(val code: String) : PairingStartResult()
    object NotAvailable : PairingStartResult()
    data class Failure(val httpStatus: Int, val detail: String) : PairingStartResult()
}

sealed class PairingGetResult {
    data class Linked(val playlist: PairedPlaylist) : PairingGetResult()
    object NotLinked : PairingGetResult()
    object NotAvailable : PairingGetResult()
    data class Failure(val httpStatus: Int, val detail: String) : PairingGetResult()
}

sealed class PairingUnlinkResult {
    object Success : PairingUnlinkResult()
    object NotAvailable : PairingUnlinkResult()
    data class Failure(val httpStatus: Int, val detail: String) : PairingUnlinkResult()
}

sealed class PlaylistResult {
    data class Success(val channels: List<Channel>) : PlaylistResult()
    data class Failure(val message: String) : PlaylistResult()
}

private class RpcResponse(val status: Int, val body: String)

/**
 * Todo lo que tiene que ver con la vinculación por código y con la
 * versión del catálogo: hablar con las funciones de Supabase y
 * descargar la lista vinculada (M3U o Xtream). La lectura de la lista
 * en sí la hace PlaylistParser.
 */
class PairingRepository {

    companion object {
        // Una lista de más de 20 MB no la descargamos: la tele podría
        // quedarse sin memoria.
        private const val MAX_PLAYLIST_BYTES = 20L * 1024 * 1024
        private const val MAX_REDIRECTS = 5
    }

    suspend fun fetchCatalogVersion(session: UserSession): CatalogVersionResult = withContext(Dispatchers.IO) {
        val rpc = callRpc("bt_catalog_version", session.accessToken)
        when {
            rpc.status in 200..299 -> try {
                val obj = JSONObject(rpc.body)
                val version = obj.optLong("version", -1L)
                val pairing = if (obj.isNull("pairing_updated_at")) null else obj.optString("pairing_updated_at")
                CatalogVersionResult.Success(version, pairing)
            } catch (e: Exception) {
                CatalogVersionResult.Failure(rpc.status, "Respuesta no válida: ${rpc.body.take(200)}")
            }
            // 404 = la función aún no está creada en Supabase.
            rpc.status == 404 -> CatalogVersionResult.NotAvailable
            else -> CatalogVersionResult.Failure(rpc.status, describeError(rpc.body))
        }
    }

    /** Pide (o recupera) el código de 8 letras que se enseña en pantalla. */
    suspend fun startPairing(session: UserSession): PairingStartResult = withContext(Dispatchers.IO) {
        val rpc = callRpc("bt_pairing_start", session.accessToken)
        when {
            rpc.status in 200..299 -> {
                // Supabase devuelve el texto entre comillas: "ABCD2345"
                val code = try {
                    JSONTokener(rpc.body.trim()).nextValue() as? String
                } catch (e: Exception) {
                    null
                }
                if (code.isNullOrBlank()) {
                    PairingStartResult.Failure(rpc.status, "Respuesta no válida: ${rpc.body.take(200)}")
                } else {
                    PairingStartResult.Success(code.trim())
                }
            }
            rpc.status == 404 -> PairingStartResult.NotAvailable
            else -> PairingStartResult.Failure(rpc.status, describeError(rpc.body))
        }
    }

    /** ¿Le han enviado ya una lista a esta tele? */
    suspend fun getPairing(session: UserSession): PairingGetResult = withContext(Dispatchers.IO) {
        val rpc = callRpc("bt_pairing_get", session.accessToken)
        when {
            rpc.status in 200..299 -> {
                val value = try {
                    val trimmed = rpc.body.trim()
                    if (trimmed.isEmpty()) null else JSONTokener(trimmed).nextValue()
                } catch (e: Exception) {
                    null
                }
                val obj = value as? JSONObject
                val type = obj?.let { stringOrNull(it, "playlist_type") }
                if (obj == null || type == null) {
                    // "null" = todavía no han enviado nada.
                    PairingGetResult.NotLinked
                } else {
                    PairingGetResult.Linked(
                        PairedPlaylist(
                            code = stringOrNull(obj, "code"),
                            label = stringOrNull(obj, "label"),
                            type = type,
                            playlistUrl = stringOrNull(obj, "playlist_url"),
                            xtreamServer = stringOrNull(obj, "xtream_server"),
                            xtreamUsername = stringOrNull(obj, "xtream_username"),
                            xtreamPassword = stringOrNull(obj, "xtream_password"),
                            updatedAt = stringOrNull(obj, "updated_at")
                        )
                    )
                }
            }
            rpc.status == 404 -> PairingGetResult.NotAvailable
            else -> PairingGetResult.Failure(rpc.status, describeError(rpc.body))
        }
    }

    /** Quita la lista vinculada: la tele vuelve a la lista oficial. */
    suspend fun unlink(session: UserSession): PairingUnlinkResult = withContext(Dispatchers.IO) {
        val rpc = callRpc("bt_pairing_unlink", session.accessToken)
        when {
            rpc.status in 200..299 -> PairingUnlinkResult.Success
            rpc.status == 404 -> PairingUnlinkResult.NotAvailable
            else -> PairingUnlinkResult.Failure(rpc.status, describeError(rpc.body))
        }
    }

    /**
     * Descarga la lista vinculada y la convierte en canales. Si algo
     * falla devuelve un motivo corto, en palabras normales, para
     * enseñarlo en pantalla (sin usuario ni contraseña).
     */
    suspend fun downloadPlaylist(playlist: PairedPlaylist): PlaylistResult = withContext(Dispatchers.IO) {
        try {
            val entries = when (playlist.type) {
                "m3u" -> {
                    val url = playlist.playlistUrl
                    if (url == null || !PlaylistParser.isHttpUrl(url)) {
                        return@withContext PlaylistResult.Failure("El enlace de la lista no es válido.")
                    }
                    PlaylistParser.parseM3u(downloadText(url.trim()))
                }
                "xtream" -> {
                    val server = playlist.xtreamServer
                    val user = playlist.xtreamUsername
                    val pass = playlist.xtreamPassword
                    if (server.isNullOrBlank() || user.isNullOrBlank() || pass == null) {
                        return@withContext PlaylistResult.Failure("Faltan datos del servidor Xtream.")
                    }
                    downloadXtream(server, user, pass)
                }
                else -> return@withContext PlaylistResult.Failure("Tipo de lista desconocido.")
            }
            val channels = PlaylistParser.toChannels(entries)
            if (channels.isEmpty()) {
                PlaylistResult.Failure("La lista no tiene canales válidos.")
            } else {
                PlaylistResult.Success(channels)
            }
        } catch (e: PlaylistDownloadException) {
            PlaylistResult.Failure(e.message ?: "No se pudo descargar la lista.")
        } catch (e: SocketTimeoutException) {
            PlaylistResult.Failure("El servidor de la lista tarda demasiado en responder.")
        } catch (e: Exception) {
            PlaylistResult.Failure("No se pudo descargar la lista (${e.javaClass.simpleName}).")
        }
    }

    private fun downloadXtream(server: String, user: String, pass: String): List<PlaylistParser.Entry> {
        // Primero los grupos (países/categorías). Si fallan no pasa nada:
        // los canales salen igual, todos en "General".
        val categoryNames = mutableMapOf<String, String>()
        try {
            val text = downloadText(PlaylistParser.xtreamApiUrl(server, user, pass, "get_live_categories"))
            val arr = JSONTokener(text.trim()).nextValue() as? JSONArray
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = stringOrNull(o, "category_id") ?: continue
                    val name = stringOrNull(o, "category_name") ?: continue
                    categoryNames[id] = name
                }
            }
        } catch (e: Exception) {
            // Seguimos sin nombres de grupo.
        }

        val text = downloadText(PlaylistParser.xtreamApiUrl(server, user, pass, "get_live_streams"))
        val arr = (
            try {
                JSONTokener(text.trim()).nextValue() as? JSONArray
            } catch (e: Exception) {
                null
            }
            ) ?: throw PlaylistDownloadException(
            "El servidor Xtream no ha dado la lista de canales. Revisa el usuario y la contraseña."
        )

        val entries = mutableListOf<PlaylistParser.Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val streamId = stringOrNull(o, "stream_id") ?: continue
            val categoryId = stringOrNull(o, "category_id")
            entries.add(
                PlaylistParser.Entry(
                    name = stringOrNull(o, "name") ?: "",
                    group = categoryId?.let { categoryNames[it] },
                    logo = stringOrNull(o, "stream_icon"),
                    url = PlaylistParser.xtreamStreamUrl(server, user, pass, streamId)
                )
            )
        }
        return entries
    }

    /**
     * Descarga un texto siguiendo redirecciones (también de http a https,
     * que Java no sigue sola), como mucho 5, y sin pasar de 20 MB.
     */
    private fun downloadText(startUrl: String): String {
        var current = URL(startUrl)
        var redirects = 0
        while (true) {
            val protocol = current.protocol.lowercase()
            if (protocol != "http" && protocol != "https") {
                throw PlaylistDownloadException("La lista redirige a un enlace no válido.")
            }
            val conn = current.openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "Boughazi-TV (Android)")
                conn.connectTimeout = 15000
                conn.readTimeout = 30000

                val status = conn.responseCode
                if (status in 300..399 && status != 304) {
                    val location = conn.getHeaderField("Location")
                        ?: throw PlaylistDownloadException("El servidor de la lista redirige sin decir a dónde.")
                    redirects++
                    if (redirects > MAX_REDIRECTS) {
                        throw PlaylistDownloadException("El enlace de la lista redirige demasiadas veces.")
                    }
                    current = URL(current, location)
                    continue
                }
                if (status !in 200..299) {
                    throw PlaylistDownloadException("El servidor de la lista respondió con error (HTTP $status).")
                }
                // contentLength es -1 si el servidor no dice el tamaño.
                if (conn.contentLength.toLong() > MAX_PLAYLIST_BYTES) {
                    throw PlaylistDownloadException("La lista es demasiado grande (más de 20 MB).")
                }
                val bytes = conn.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_PLAYLIST_BYTES) {
                            throw PlaylistDownloadException("La lista es demasiado grande (más de 20 MB).")
                        }
                        out.write(buffer, 0, read)
                    }
                    out.toByteArray()
                }
                return String(bytes, Charsets.UTF_8)
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun callRpc(name: String, accessToken: String): RpcResponse {
        return try {
            val conn = URL("${SupabaseConfig.URL}/rest/v1/rpc/$name").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("apikey", SupabaseConfig.ANON_KEY)
            conn.setRequestProperty("Authorization", "Bearer $accessToken")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            OutputStreamWriter(conn.outputStream).use { it.write("{}") }

            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            RpcResponse(status, text)
        } catch (e: Exception) {
            RpcResponse(-1, "Fallo de conexión: ${e.javaClass.simpleName} — ${e.message}")
        }
    }

    // optString devuelve el texto "null" cuando el valor es null: por eso
    // se comprueba antes con isNull.
    private fun stringOrNull(obj: JSONObject, key: String): String? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return obj.optString(key).trim().takeIf { it.isNotEmpty() }
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

/** Fallo al descargar la lista, con un motivo ya escrito para la pantalla. */
private class PlaylistDownloadException(message: String) : IOException(message)
