package tv.boughazi.app

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Reproductor de películas a pantalla completa. Vale para enlaces HLS
 * (.m3u8, lo que genera Bunny Stream) y para MP4 directos. Con el mando:
 * OK enseña los controles, izquierda/derecha adelantan o atrasan y
 * "atrás" vuelve a la lista de películas.
 */
class MoviePlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var errorText: TextView
    private var url: String = ""
    private var resumePosition = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        url = intent.getStringExtra(EXTRA_URL).orEmpty()
        playerView = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            useController = true
            controllerShowTimeoutMs = 4000
        }
        // Mensaje de error fijo en pantalla (un aviso breve se pierde en la tele).
        errorText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            textSize = 16f
            setPadding(32, 20, 32, 20)
            visibility = View.GONE
        }
        val root = FrameLayout(this)
        root.addView(playerView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(errorText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP))
        setContentView(root)
        if (savedInstanceState != null) {
            resumePosition = savedInstanceState.getLong(KEY_POSITION, 0L)
        }
    }

    override fun onStart() {
        super.onStart()
        if (url.isBlank()) {
            Toast.makeText(this, "Esta película no tiene enlace de vídeo", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val p = ExoPlayer.Builder(this).build()
        player = p
        playerView.player = p
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Código del fallo y, si lo hay, la causa (por ejemplo
                // "Response code: 403" si el servidor no deja ver el vídeo).
                val causa = error.cause?.message?.take(120) ?: ""
                val enlace = url.take(90)
                errorText.text = "No se pudo reproducir la película\n" +
                    "Motivo: ${error.errorCodeName}" +
                    (if (causa.isNotBlank()) "\nDetalle: $causa" else "") +
                    "\nEnlace: $enlace"
                errorText.visibility = View.VISIBLE
            }
        })
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        if (resumePosition > 0) p.seekTo(resumePosition)
        p.playWhenReady = true
    }

    override fun onStop() {
        super.onStop()
        player?.let {
            resumePosition = it.currentPosition
            it.release()
        }
        player = null
        playerView.player = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_POSITION, resumePosition)
    }

    companion object {
        const val EXTRA_URL = "movie_url"
        private const val KEY_POSITION = "position"
    }
}
