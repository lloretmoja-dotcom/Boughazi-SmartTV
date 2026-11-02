package tv.boughazi.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Actualizador interno.
 *
 * Al abrir la app mira en Supabase (tabla app_version) cuál es la última
 * versión PUBLICADA. Las versiones "pendientes" las esconde la base de
 * datos, así que solo llegan a la gente cuando tú das el OK.
 *
 * Si hay una versión más nueva que la instalada, enseña un aviso, descarga
 * el APK y abre el instalador de Android. Android siempre pide pulsar
 * "Instalar" una vez: fuera de la Play Store no se puede evitar.
 */
data class AppUpdate(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val notes: String,
    val mandatory: Boolean
)

class UpdateManager(private val activity: Activity) {

    private var checkedThisSession = false
    private var dialog: AlertDialog? = null

    /** Llamar una vez al entrar en la pantalla principal. */
    suspend fun checkAndPrompt() {
        if (checkedThisSession) return
        checkedThisSession = true
        val update = fetchLatestPublished() ?: return
        if (update.versionCode <= installedVersionCode()) return
        if (!update.apkUrl.startsWith("https://")) return
        if (activity.isFinishing || activity.isDestroyed) return
        showUpdateDialog(update)
    }

    private fun installedVersionCode(): Long = try {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        PackageInfoCompat.getLongVersionCode(info)
    } catch (e: Exception) {
        Long.MAX_VALUE // si no se puede saber, mejor no molestar
    }

    private suspend fun fetchLatestPublished(): AppUpdate? = withContext(Dispatchers.IO) {
        try {
            val url = URL(
                "${SupabaseConfig.URL}/rest/v1/app_version" +
                    "?estado=eq.publicada&order=version_code.desc&limit=1" +
                    "&select=version_code,version_name,apk_url,notas,obligatoria"
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("apikey", SupabaseConfig.ANON_KEY)
            conn.setRequestProperty("Authorization", "Bearer ${SupabaseConfig.ANON_KEY}")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            if (conn.responseCode !in 200..299) return@withContext null
            val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            if (arr.length() == 0) return@withContext null
            val row = arr.getJSONObject(0)
            AppUpdate(
                versionCode = row.getLong("version_code"),
                versionName = row.optString("version_name", ""),
                apkUrl = row.getString("apk_url"),
                notes = if (row.isNull("notas")) "" else row.getString("notas"),
                mandatory = row.optBoolean("obligatoria", false)
            )
        } catch (e: Exception) {
            null // sin internet o sin tabla: la app sigue como si nada
        }
    }

    private fun showUpdateDialog(update: AppUpdate) {
        val message = buildString {
            append("Versión ${update.versionName}")
            if (update.notes.isNotBlank()) append("\n\n").append(update.notes)
        }
        val builder = AlertDialog.Builder(activity)
            .setTitle("Nueva versión disponible")
            .setMessage(message)
            .setCancelable(!update.mandatory)
            .setPositiveButton("Instalar", null) // se asigna abajo para que no se cierre solo
        if (!update.mandatory) builder.setNegativeButton("Más tarde", null)
        val d = builder.create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                requestFocus()
                setOnClickListener {
                    d.dismiss()
                    startInstall(update)
                }
            }
            d.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener { d.dismiss() }
        }
        dialog = d
        d.show()
    }

    private fun startInstall(update: AppUpdate) {
        // Android 8+: la app necesita permiso para instalar "apps desconocidas".
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            Toast.makeText(
                activity,
                "Activa \"Permitir de esta fuente\" y vuelve a abrir la app",
                Toast.LENGTH_LONG
            ).show()
            try {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                )
            } catch (e: Exception) {
                activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            }
            checkedThisSession = false // al volver, se ofrece otra vez
            return
        }

        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        val label = TextView(activity).apply {
            text = "Descargando actualización…"
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 48, 64, 32)
            addView(label)
            addView(bar)
        }
        val progress = AlertDialog.Builder(activity)
            .setView(box)
            .setCancelable(false)
            .create()
        progress.show()

        Thread {
            val file = try {
                download(update.apkUrl) { percent ->
                    activity.runOnUiThread {
                        bar.isIndeterminate = false
                        bar.progress = percent
                        label.text = "Descargando actualización… $percent%"
                    }
                }
            } catch (e: Exception) {
                null
            }
            activity.runOnUiThread {
                progress.dismiss()
                if (file == null) {
                    Toast.makeText(
                        activity,
                        "No se pudo descargar la actualización. Inténtalo más tarde.",
                        Toast.LENGTH_LONG
                    ).show()
                    checkedThisSession = false
                } else {
                    openInstaller(file)
                }
            }
        }.start()
    }

    private fun download(apkUrl: String, onProgress: (Int) -> Unit): File? {
        val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "update.apk")
        val conn = URL(apkUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        if (conn.responseCode !in 200..299) return null
        val total = conn.contentLength.toLong()
        var done = 0L
        var lastPercent = -1
        conn.inputStream.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    done += n
                    if (total > 0) {
                        val p = (done * 100 / total).toInt()
                        if (p != lastPercent) {
                            lastPercent = p
                            onProgress(p)
                        }
                    }
                }
            }
        }
        return if (target.length() > 0) target else null
    }

    private fun openInstaller(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                activity, "${activity.packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(activity, "No se pudo abrir el instalador", Toast.LENGTH_LONG).show()
        }
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }
}
