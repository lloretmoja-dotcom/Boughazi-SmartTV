package tv.boughazi.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Habla directamente con la API de autenticación de Supabase (por
 * eso no hace falta ninguna librería extra). Se encarga de:
 *  - iniciar sesión / registrarse con Gmail y contraseña
 *  - "olvidé mi contraseña" (envía un correo real al Gmail)
 * Ya NO existe ninguna comprobación de pago/suscripción: con tener
 * la cuenta creada es suficiente para entrar (después toca el paso
 * del código de activación, que va aparte, ver CodeRepository).
 */
sealed class AuthResult {
    data class Success(val session: UserSession) : AuthResult()
    data class Failure(val message: String) : AuthResult()
}

class AuthRepository {

    suspend fun signIn(email: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("${SupabaseConfig.URL}/auth/v1/token?grant_type=password")
            val body = JSONObject().apply {
                put("email", email)
                put("password", password)
            }
            val json = postJson(url, body)
            if (json.has("access_token")) {
                AuthResult.Success(toSession(json))
            } else {
                AuthResult.Failure(errorMessage(json))
            }
        } catch (e: Exception) {
            AuthResult.Failure(e.message ?: "No se pudo conectar. Revisa tu conexión a internet.")
        }
    }

    suspend fun signUp(email: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            // "redirect_to" es la página a la que Supabase manda a la
            // persona después de tocar el enlace de confirmación de su
            // correo (ver reset-password.html).
            val redirect = URLEncoder.encode(SupabaseConfig.RESET_PASSWORD_URL, "UTF-8")
            val url = URL("${SupabaseConfig.URL}/auth/v1/signup?redirect_to=$redirect")
            val body = JSONObject().apply {
                put("email", email)
                put("password", password)
            }
            val json = postJson(url, body)
            if (json.has("access_token")) {
                AuthResult.Success(toSession(json))
            } else if (json.has("id")) {
                // Cuenta creada pero requiere confirmar el correo antes de dar sesión.
                AuthResult.Failure("Cuenta creada. Revisa tu Gmail para confirmar la cuenta y luego inicia sesión.")
            } else {
                AuthResult.Failure(errorMessage(json))
            }
        } catch (e: Exception) {
            AuthResult.Failure(e.message ?: "No se pudo conectar. Revisa tu conexión a internet.")
        }
    }

    /**
     * El token de acceso de Supabase caduca (normalmente en 1 hora).
     * Si ha pasado tiempo entre iniciar sesión y hacer otra cosa (por
     * ejemplo activar el código), hay que renovarlo con el refresh_token
     * guardado, en vez de obligar a la persona a salir y volver a entrar.
     */
    suspend fun refreshSession(refreshToken: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("${SupabaseConfig.URL}/auth/v1/token?grant_type=refresh_token")
            val body = JSONObject().apply { put("refresh_token", refreshToken) }
            val json = postJson(url, body)
            if (json.has("access_token")) {
                AuthResult.Success(toSession(json))
            } else {
                AuthResult.Failure(errorMessage(json))
            }
        } catch (e: Exception) {
            AuthResult.Failure(e.message ?: "No se pudo renovar la sesión.")
        }
    }

    /**
     * Devuelve null si el correo se ha enviado, o el mensaje de error si
     * no se pudo. Antes devolvía siempre Failure, y el aviso de "te hemos
     * enviado un correo" salía en rojo como si fuera un error.
     */
    suspend fun sendPasswordReset(email: String): String? = withContext(Dispatchers.IO) {
        try {
            // Igual que arriba: mandamos a la persona a reset-password.html,
            // que es la página donde de verdad puede escribir su
            // contraseña nueva.
            val redirect = URLEncoder.encode(SupabaseConfig.RESET_PASSWORD_URL, "UTF-8")
            val url = URL("${SupabaseConfig.URL}/auth/v1/recover?redirect_to=$redirect")
            val body = JSONObject().apply { put("email", email) }
            postJson(url, body)
            // Supabase siempre responde con éxito aquí (por seguridad, no
            // revela si el correo existe o no), así que damos el aviso normal.
            null
        } catch (e: Exception) {
            e.message ?: "No se pudo conectar. Revisa tu conexión a internet."
        }
    }

    private fun toSession(json: JSONObject): UserSession {
        val user = json.getJSONObject("user")
        return UserSession(
            accessToken = json.getString("access_token"),
            refreshToken = json.getString("refresh_token"),
            userId = user.getString("id"),
            email = user.optString("email", ""),
            hasLinkedCode = false
        )
    }

    private fun errorMessage(json: JSONObject): String {
        return json.optString("error_description", json.optString("msg", "Correo o contraseña incorrectos."))
    }

    private fun postJson(url: URL, body: JSONObject): JSONObject {
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("apikey", SupabaseConfig.ANON_KEY)
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            JSONObject()
        }
    }
}
