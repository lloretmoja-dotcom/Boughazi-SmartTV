package tv.boughazi.app

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
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
        setContentView(playerView)
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
                Toast.makeText(
                    this@MoviePlayerActivity,
                    "No se pudo reproducir la película",
                    Toast.LENGTH_LONG
                ).show()
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
