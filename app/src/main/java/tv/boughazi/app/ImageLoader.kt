package tv.boughazi.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * Cargador de logos muy sencillo (sin librerías externas): descarga
 * la imagen una vez y la guarda en memoria para no volver a
 * descargarla cada vez que aparece el mismo canal.
 */
object ImageLoader {
    // Como mucho unos 8 MB de logos en memoria: antes se guardaban todos
    // sin límite y, con muchos canales, la tele podía quedarse sin memoria.
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(scope: CoroutineScope, url: String?, into: ImageView) {
        // Se apunta qué logo se espera en esta imagen. Al hacer zapping
        // rápido, si llega tarde el logo de un canal anterior, se descarta
        // en lugar de taparle el logo al canal que se está viendo.
        into.tag = url
        if (url.isNullOrBlank()) {
            into.setImageDrawable(null)
            return
        }
        cache.get(url)?.let {
            into.setImageBitmap(it)
            return
        }
        into.setImageDrawable(null)
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    BitmapFactory.decodeStream(URL(url).openStream())
                } catch (e: Exception) {
                    null
                }
            }
            if (bitmap != null) {
                cache.put(url, bitmap)
                if (into.tag == url) into.setImageBitmap(bitmap)
            }
        }
    }
}
