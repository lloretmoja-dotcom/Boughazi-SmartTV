package tv.boughazi.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Resultado de intentar activar un código. A diferencia de antes, si
 * falla guardamos el motivo REAL (código HTTP + texto que responde
 * Supabase) en vez de solo decir "false" — así se puede ver en
 * pantalla qué está pasando de verdad, en lugar de adivinar.
 */
sealed class RedeemResult {
    object Success : RedeemResult()
    data class Failure(val httpStatus: Int, val detail: String) : RedeemResult()
}

private data class PatchResult(val status: Int, val array: JSONArray?, val rawBody: String)

class CodeRepository {

    /**
     * Mira en Supabase si esta cuenta ya tiene un código vinculado.
     * Devuelve null si la sesión ha caducado (HTTP 401/403), para que
     * quien llama renueve la sesión y lo vuelva a intentar, en vez de
     * creer que la cuenta no tiene código y pedir (y gastar) otro.
     */
    suspend fun checkAlreadyLinked(session: UserSession): Boolean? = withContext(Dispatchers.IO) {
        try {
            val url = URL(
                "${SupabaseConfig.URL}/rest/v1/bt_viewers?id=eq.${encode(session.userId)}&select=linked_code"
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("apikey", SupabaseConfig.ANON_KEY)
            conn.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            val status = conn.responseCode
            if (status == 401 || status == 403) return@withContext null
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: "[]"
            val arr = JSONArray(text)
            if (arr.length() == 0) return@withContext false
            val row = arr.getJSONObject(0)
            !row.isNull("linked_code")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Activa un código. Primero usa la función segura de Supabase
     * "bt_redeem_code", que reclama el código y vincula la cuenta en un
     * solo paso dentro de la base de datos (o las dos cosas, o ninguna).
     * Si esa función todavía no existe en Supabase (no se ha ejecutado
     * el SQL de supabase/seguridad-activacion.sql), se usa el método
     * antiguo de dos pasos para que la app siga funcionando mientras.
     */
    suspend fun redeemCode(session: UserSession, code: String): RedeemResult =
        withContext(Dispatchers.IO) {
            val cleanCode = code.trim().uppercase()
            val rpc = postJson(
                URL("${SupabaseConfig.URL}/rest/v1/rpc/bt_redeem_code"),
                session.accessToken,
                JSONObject().apply { put("p_code", cleanCode) }
            )
            when {
                rpc.status in 200..299 ->
                    if (rpc.rawBody.trim() == "true") {
                        RedeemResult.Success
                    } else {
                        RedeemResult.Failure(rpc.status, "Código no válido, desactivado o ya usado.")
                    }
                // 404 = la función aún no está creada en Supabase.
                rpc.status == 404 -> redeemCodeLegacy(session, cleanCode)
                else -> RedeemResult.Failure(rpc.status, describeError(rpc.rawBody))
            }
        }

    private fun redeemCodeLegacy(session: UserSession, cleanCode: String): RedeemResult {
        val nowIso = isoNow()
        val claimUrl = URL(
            "${SupabaseConfig.URL}/rest/v1/bt_access_codes" +
                "?code=eq.${encode(cleanCode)}&used_by_email=is.null&active=eq.true"
        )
        val claimBody = JSONObject().apply {
            put("used_by_email", session.email)
            put("used_at", nowIso)
        }
        val claimResult = patchJson(claimUrl, session.accessToken, claimBody)

        if (claimResult.status !in 200..299) {
            return RedeemResult.Failure(claimResult.status, describeError(claimResult.rawBody))
        }
        if (claimResult.array == null || claimResult.array.length() == 0) {
            return RedeemResult.Failure(
                claimResult.status,
                "No se actualizó ninguna fila (respuesta vacía: '${claimResult.rawBody}')"
            )
        }

        val viewerUrl = URL("${SupabaseConfig.URL}/rest/v1/bt_viewers?id=eq.${encode(session.userId)}")
        val viewerBody = JSONObject().apply {
            put("linked_code", cleanCode)
            put("linked_at", nowIso)
        }
        val viewerResult = patchJson(viewerUrl, session.accessToken, viewerBody)
        if (viewerResult.status !in 200..299 || viewerResult.array == null || viewerResult.array.length() == 0) {
            // No se pudo vincular la cuenta: devolvemos el código para que
            // no quede gastado sin que la persona pueda entrar.
            val releaseUrl = URL(
                "${SupabaseConfig.URL}/rest/v1/bt_access_codes" +
                    "?code=eq.${encode(cleanCode)}&used_by_email=eq.${encode(session.email)}"
            )
            patchJson(releaseUrl, session.accessToken, JSONObject().apply {
                put("used_by_email", JSONObject.NULL)
                put("used_at", JSONObject.NULL)
            })
            return RedeemResult.Failure(
                viewerResult.status,
                "No se pudo vincular el código a tu cuenta: ${describeError(viewerResult.rawBody)}"
            )
        }
        return RedeemResult.Success
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun postJson(url: URL, accessToken: String, body: JSONObject): PatchResult {
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("apikey", SupabaseConfig.ANON_KEY)
        conn.setRequestProperty("Authorization", "Bearer $accessToken")
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        return PatchResult(status, null, text)
    }

    private fun describeError(rawBody: String): String {
        return try {
            val obj = JSONObject(rawBody)
            obj.optString("message", obj.optString("msg", rawBody)).ifBlank { rawBody }
        } catch (e: Exception) {
            rawBody
        }
    }

    private fun isoNow(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun patchJson(url: URL, accessToken: String, body: JSONObject): PatchResult {
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "PATCH"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("apikey", SupabaseConfig.ANON_KEY)
        conn.setRequestProperty("Authorization", "Bearer $accessToken")
        conn.setRequestProperty("Prefer", "return=representation")
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        val array = try {
            JSONArray(text)
        } catch (e: Exception) {
            null
        }
        return PatchResult(status, array, text)
    }
}
