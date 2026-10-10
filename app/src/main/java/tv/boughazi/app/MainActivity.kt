package tv.boughazi.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val authRepository = AuthRepository()
    private val codeRepository = CodeRepository()
    private val channelRepository = ChannelRepository()
    private val presenceRepository = PresenceRepository()
    private lateinit var sessionManager: SessionManager

    private var session: UserSession? = null
    private var allChannels: List<Channel> = emptyList()
    private var categories: List<String> = emptyList()
    private var currentIndex = -1

    // Qué país/categoría se está viendo AHORA MISMO en la lista de canales
    // (si está abierta). Sirve para poder refrescar esa lista sola cuando
    // llegan canales nuevos o se borran caídos, sin que la persona tenga
    // que cerrar la aplicación y volver a abrirla para verlo.
    private var currentlyViewedCategory: String? = null

    private var exoPlayer: ExoPlayer? = null
    private val numberBuffer = StringBuilder()
    private val handler = Handler(Looper.getMainLooper())
    private var osdHideRunnable: Runnable? = null
    private var numberEntryRunnable: Runnable? = null
    private var presenceRunnable: Runnable? = null
    private var channelRefreshRunnable: Runnable? = null

    companion object {
        private const val CHANNEL_REFRESH_INTERVAL_MS = 5 * 60 * 1000L // 5 minutos
    }

    private lateinit var welcomeSection: View
    private lateinit var loginPanelSection: View
    private lateinit var signUpPanelSection: View
    private lateinit var forgotPanelSection: View
    private lateinit var codeSection: View
    private lateinit var mainSection: View
    private lateinit var playerView: PlayerView
    private lateinit var categoriesColumn: View
    private lateinit var categoriesList: RecyclerView
    private lateinit var channelsList: RecyclerView
    private lateinit var osdContainer: View
    private lateinit var osdNumber: TextView
    private lateinit var osdName: TextView
    private lateinit var loadingText: TextView
    private lateinit var debugInfoText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sessionManager = SessionManager(this)
        MobileAds.initialize(this)

        welcomeSection = findViewById(R.id.welcomeSection)
        loginPanelSection = findViewById(R.id.loginPanelSection)
        signUpPanelSection = findViewById(R.id.signUpPanelSection)
        forgotPanelSection = findViewById(R.id.forgotPanelSection)
        codeSection = findViewById(R.id.codeSection)
        mainSection = findViewById(R.id.mainSection)
        playerView = findViewById(R.id.playerView)
        categoriesColumn = findViewById(R.id.categoriesColumn)
        categoriesList = findViewById(R.id.categoriesList)
        channelsList = findViewById(R.id.channelsList)
        osdContainer = findViewById(R.id.osdContainer)
        osdNumber = findViewById(R.id.osdNumber)
        osdName = findViewById(R.id.osdName)
        loadingText = findViewById(R.id.loadingText)
        debugInfoText = findViewById(R.id.debugInfoText)

        setupWelcomeSection()
        setupLoginPanel()
        setupSignUpPanel()
        setupForgotPanel()
        setupCodeSection()
        setupAdBanner()

        val saved = sessionManager.load()
        if (saved == null) {
            showOnly(welcomeSection)
        } else {
            session = saved
            if (saved.hasLinkedCode) {
                enterMainSection()
            } else {
                lifecycleScope.launch { goToCodeOrMain(saved) }
            }
        }
    }

    /**
     * La pantalla de bienvenida solo tiene botones. Cada uno abre su
     * propio panel aparte, con sus propias casillas — así nunca hay
     * dudas sobre para qué sirve cada campo.
     */
    private fun setupWelcomeSection() {
        findViewById<View>(R.id.welcomeEntrarBtn).setOnClickListener {
            showOnly(loginPanelSection)
        }
        findViewById<View>(R.id.welcomeCrearCuentaBtn).setOnClickListener {
            showOnly(signUpPanelSection)
        }
        findViewById<View>(R.id.welcomeOlvidoBtn).setOnClickListener {
            showOnly(forgotPanelSection)
        }
    }

    private fun setupLoginPanel() {
        val emailField = findViewById<EditText>(R.id.loginEmail)
        val passwordField = findViewById<EditText>(R.id.loginPassword)
        val errorText = findViewById<TextView>(R.id.loginError)

        findViewById<View>(R.id.loginSubmitBtn).setOnClickListener {
            val email = emailField.text.toString().trim()
            val password = passwordField.text.toString()
            if (email.isEmpty() || password.isEmpty()) {
                showError(errorText, "Escribe tu correo y tu contraseña.")
                return@setOnClickListener
            }
            lifecycleScope.launch {
                when (val result = authRepository.signIn(email, password)) {
                    is AuthResult.Success -> onAuthSuccess(result.session)
                    is AuthResult.Failure -> showError(errorText, result.message)
                }
            }
        }

        findViewById<View>(R.id.loginBackBtn).setOnClickListener {
            passwordField.text.clear()
            errorText.visibility = View.GONE
            showOnly(welcomeSection)
        }
    }

    private fun setupSignUpPanel() {
        val emailField = findViewById<EditText>(R.id.signUpEmail)
        val passwordField = findViewById<EditText>(R.id.signUpPassword)
        val errorText = findViewById<TextView>(R.id.signUpError)

        findViewById<View>(R.id.signUpSubmitBtn).setOnClickListener {
            val email = emailField.text.toString().trim()
            val password = passwordField.text.toString()
            if (email.isEmpty() || password.length < 6) {
                showError(errorText, "Escribe un correo y una contraseña de al menos 6 caracteres.")
                return@setOnClickListener
            }
            lifecycleScope.launch {
                when (val result = authRepository.signUp(email, password)) {
                    is AuthResult.Success -> onAuthSuccess(result.session)
                    is AuthResult.Failure -> showError(errorText, result.message)
                }
            }
        }

        findViewById<View>(R.id.signUpBackBtn).setOnClickListener {
            passwordField.text.clear()
            errorText.visibility = View.GONE
            showOnly(welcomeSection)
        }
    }

    private fun setupForgotPanel() {
        val emailField = findViewById<EditText>(R.id.forgotEmail)
        val errorText = findViewById<TextView>(R.id.forgotError)
        val statusText = findViewById<TextView>(R.id.forgotStatus)

        findViewById<View>(R.id.forgotSubmitBtn).setOnClickListener {
            val email = emailField.text.toString().trim()
            statusText.visibility = View.GONE
            if (email.isEmpty()) {
                showError(errorText, "Escribe tu correo arriba y vuelve a tocar aquí.")
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val result = authRepository.sendPasswordReset(email)
                if (result is AuthResult.Failure) {
                    showError(errorText, result.message)
                } else {
                    errorText.visibility = View.GONE
                    statusText.text = "Te hemos mandado un enlace a tu correo para cambiar la contraseña."
                    statusText.visibility = View.VISIBLE
                }
            }
        }

        findViewById<View>(R.id.forgotBackBtn).setOnClickListener {
            errorText.visibility = View.GONE
            statusText.visibility = View.GONE
            showOnly(welcomeSection)
        }
    }

    private fun onAuthSuccess(newSession: UserSession) {
        session = newSession
        sessionManager.save(newSession)
        lifecycleScope.launch { goToCodeOrMain(newSession) }
    }

    /**
     * Si la cuenta ya tiene un código vinculado, entra directamente a la
     * tele; si no, pide el código. Si la sesión guardada ha caducado, la
     * renueva primero: antes se daba por hecho que "no tiene código" y se
     * pedía (y gastaba) un código nuevo a quien ya tenía uno.
     */
    private suspend fun goToCodeOrMain(start: UserSession) {
        var current = start
        var linked = codeRepository.checkAlreadyLinked(current)
        if (linked == null) {
            val refreshed = authRepository.refreshSession(current.refreshToken)
            if (refreshed is AuthResult.Success) {
                current = refreshed.session.copy(hasLinkedCode = current.hasLinkedCode)
                session = current
                sessionManager.save(current)
                linked = codeRepository.checkAlreadyLinked(current)
            }
        }
        if (linked == true) {
            current.hasLinkedCode = true
            sessionManager.markCodeLinked()
            enterMainSection()
        } else {
            showOnly(codeSection)
        }
    }

    private fun setupCodeSection() {
        val codeInput = findViewById<EditText>(R.id.codeInput)
        val errorText = findViewById<TextView>(R.id.codeError)

        findViewById<View>(R.id.codeSubmitBtn).setOnClickListener {
            val code = codeInput.text.toString().trim()
            val currentSession = session ?: return@setOnClickListener
            if (code.isEmpty()) {
                showError(errorText, "Escribe el código que te han dado.")
                return@setOnClickListener
            }
            lifecycleScope.launch {
                attemptRedeem(currentSession, code, errorText, allowRetry = true)
            }
        }
    }

    private suspend fun attemptRedeem(
        currentSession: UserSession,
        code: String,
        errorText: TextView,
        allowRetry: Boolean
    ) {
        when (val result = codeRepository.redeemCode(currentSession, code)) {
            is RedeemResult.Success -> {
                currentSession.hasLinkedCode = true
                sessionManager.markCodeLinked()
                enterMainSection()
            }
            is RedeemResult.Failure -> {
                if (allowRetry && (result.httpStatus == 401 || result.httpStatus == 403)) {
                    when (val refreshed = authRepository.refreshSession(currentSession.refreshToken)) {
                        is AuthResult.Success -> {
                            val renewed = refreshed.session.copy(hasLinkedCode = currentSession.hasLinkedCode)
                            session = renewed
                            sessionManager.save(renewed)
                            attemptRedeem(renewed, code, errorText, allowRetry = false)
                        }
                        is AuthResult.Failure -> {
                            showError(
                                errorText,
                                "Tu sesión caducó y no se pudo renovar. Cierra la app, entra otra vez con tu Gmail y prueba el código de nuevo."
                            )
                        }
                    }
                } else {
                    showError(
                        errorText,
                        "Ese código no es válido o ya se ha usado. (Detalle: HTTP ${result.httpStatus} — ${result.detail})"
                    )
                }
            }
        }
    }

    private fun enterMainSection() {
        showOnly(mainSection)
        loadingText.visibility = View.VISIBLE
        val currentSession = session ?: return

        exoPlayer = ExoPlayer.Builder(this).build().also { player ->
            playerView.player = player
            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    handler.postDelayed({
                        if (currentIndex in allChannels.indices) {
                            playChannel(currentIndex)
                        }
                    }, 3000)
                }
            })
        }

        categoriesList.layoutManager = LinearLayoutManager(this)
        channelsList.layoutManager = LinearLayoutManager(this)

        lifecycleScope.launch {
            loadingText.text = "Cargando canales…"
            loadChannels(currentSession, allowRetry = true)
        }
        startChannelAutoRefresh()
    }

    private fun startChannelAutoRefresh() {
        channelRefreshRunnable?.let { handler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                val currentSession = session
                if (currentSession != null) {
                    lifecycleScope.launch { refreshChannelsQuietly(currentSession, allowRetry = true) }
                }
                handler.postDelayed(this, CHANNEL_REFRESH_INTERVAL_MS)
            }
        }
        channelRefreshRunnable = runnable
        handler.postDelayed(runnable, CHANNEL_REFRESH_INTERVAL_MS)
    }

    private fun updateDebugInfo(loaded: Int, categoriesCount: Int, totalOnServer: Int?) {
        // Aviso de pruebas ya no visible: la app está lista para usuarios
        // reales. Se deja la función (no se borra) por si hiciera falta
        // reactivar el aviso más adelante para investigar algo.
        debugInfoText.visibility = View.GONE
    }

    private suspend fun refreshChannelsQuietly(currentSession: UserSession, allowRetry: Boolean) {
        when (val result = channelRepository.fetchChannels(currentSession)) {
            is ChannelsResult.Success -> {
                val hadChannelsBefore = allChannels.isNotEmpty()
                val playingId = allChannels.getOrNull(currentIndex)?.id

                allChannels = result.channels
                categories = allChannels.map { it.category }.distinct()
                updateDebugInfo(allChannels.size, categories.size, result.totalReportedByServer)

                categoriesList.adapter = RowAdapter(
                    categories.map { RowItem(flag = CountryFlags.flagFor(it), title = it) }
                ) { position -> onCategorySelected(categories[position]) }

                // Si la persona tiene abierta la lista de canales de un país
                // en este momento, la volvemos a rellenar con los datos
                // recién traídos — así, si algún canal se ha borrado (por
                // estar caído) o se ha añadido uno nuevo, se ve solo, sin
                // tener que cerrar la aplicación y volver a abrirla.
                currentlyViewedCategory
                    ?.takeIf { it in categories && channelsList.visibility == View.VISIBLE }
                    ?.let { onCategorySelected(it) }

                currentIndex = playingId
                    ?.let { id -> allChannels.indexOfFirst { it.id == id } }
                    ?.takeIf { it >= 0 }
                    ?: currentIndex.coerceIn(0, (allChannels.size - 1).coerceAtLeast(0))

                if (!hadChannelsBefore && allChannels.isNotEmpty()) {
                    loadingText.visibility = View.GONE
                    playChannel(0)
                    startPresenceHeartbeat()
                }
            }
            is ChannelsResult.Failure -> {
                if (allowRetry && (result.httpStatus == 401 || result.httpStatus == 403)) {
                    when (val refreshed = authRepository.refreshSession(currentSession.refreshToken)) {
                        is AuthResult.Success -> {
                            val renewed = refreshed.session.copy(hasLinkedCode = currentSession.hasLinkedCode)
                            session = renewed
                            sessionManager.save(renewed)
                            refreshChannelsQuietly(renewed, allowRetry = false)
                        }
                        is AuthResult.Failure -> {
                            // Fallo silencioso: lo reintentamos solos en el próximo ciclo.
                        }
                    }
                }
                // Fallo silencioso también en los demás casos: se reintenta solo
                // en el siguiente ciclo, sin mostrar ningún aviso en pantalla.
            }
        }
    }

    private suspend fun loadChannels(currentSession: UserSession, allowRetry: Boolean) {
        when (val result = channelRepository.fetchChannels(currentSession)) {
            is ChannelsResult.Success -> {
                allChannels = result.channels
                categories = allChannels.map { it.category }.distinct()
                loadingText.visibility = View.GONE

                if (allChannels.isEmpty()) {
                    loadingText.text = "Todavía no hay canales disponibles."
                    loadingText.visibility = View.VISIBLE
                    return
                }

                categoriesList.adapter = RowAdapter(
                    categories.map { RowItem(flag = CountryFlags.flagFor(it), title = it) }
                ) { position -> onCategorySelected(categories[position]) }

                updateDebugInfo(allChannels.size, categories.size, result.totalReportedByServer)

                playChannel(0)
                startPresenceHeartbeat()
            }
            is ChannelsResult.Failure -> {
                if (allowRetry && (result.httpStatus == 401 || result.httpStatus == 403)) {
                    when (val refreshed = authRepository.refreshSession(currentSession.refreshToken)) {
                        is AuthResult.Success -> {
                            val renewed = refreshed.session.copy(hasLinkedCode = currentSession.hasLinkedCode)
                            session = renewed
                            sessionManager.save(renewed)
                            loadChannels(renewed, allowRetry = false)
                        }
                        is AuthResult.Failure -> {
                            loadingText.text = "Tu sesión ha caducado. Sal de la app y vuelve a entrar con tu Gmail."
                            loadingText.visibility = View.VISIBLE
                        }
                    }
                } else {
                    loadingText.text =
                        "No se pudieron cargar los canales. (Detalle: HTTP ${result.httpStatus} — ${result.detail})"
                    loadingText.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun onCategorySelected(category: String) {
        currentlyViewedCategory = category
        val channelsInCategory = allChannels.filter { it.category == category }
        channelsList.adapter = RowAdapter(
            channelsInCategory.mapIndexed { idx, it -> RowItem(title = "${idx + 1}  ${it.name}") }
        ) { position ->
            val chosen = channelsInCategory[position]
            val flatIndex = allChannels.indexOfFirst { it.id == chosen.id }
            if (flatIndex >= 0) playChannel(flatIndex)
            hideChannelBrowser()
        }
        channelsList.visibility = View.VISIBLE
        channelsList.requestFocus()
    }

    private fun showChannelBrowser() {
        categoriesColumn.visibility = View.VISIBLE
        categoriesList.requestFocus()
    }

    /**
     * Al pulsar OK mientras se está viendo un canal, abrimos la lista
     * directamente en el país y canal donde se está en ese momento,
     * para que se vea de un vistazo "dónde estoy" y se pueda cambiar
     * sin tener que buscar desde el principio.
     */
    private fun openBrowserAtCurrentChannel() {
        categoriesColumn.visibility = View.VISIBLE
        val current = allChannels.getOrNull(currentIndex)
        if (current != null) {
            onCategorySelected(current.category)
            channelsList.requestFocus()
        } else {
            categoriesList.requestFocus()
        }
    }

    private fun hideChannelBrowser() {
        categoriesColumn.visibility = View.GONE
        channelsList.visibility = View.GONE
        currentlyViewedCategory = null
        playerView.requestFocus()
    }

    private fun playChannel(index: Int) {
        if (index !in allChannels.indices) return
        currentIndex = index
        val channel = allChannels[index]
        exoPlayer?.apply {
            setMediaItem(MediaItem.fromUri(channel.streamUrl))
            prepare()
            playWhenReady = true
        }
        showOsd(channel)
        updatePresenceChannel(channel.id)
    }

    // Al cambiar de canal con el mando (CH+/CH-), nos quedamos siempre
    // dentro del mismo país: al llegar al último canal del país se
    // vuelve al primero (canal 1), y al revés. NO salta solo a otro
    // país — para eso hay que abrir el buscador (OK) y elegirlo a mano.
    private fun zapNext() {
        val current = allChannels.getOrNull(currentIndex) ?: return
        val channelsInCategory = allChannels.filter { it.category == current.category }
        val posInCategory = channelsInCategory.indexOfFirst { it.id == current.id }
        if (posInCategory < 0 || channelsInCategory.isEmpty()) return
        val nextChannel = channelsInCategory[(posInCategory + 1) % channelsInCategory.size]
        val flatIndex = allChannels.indexOfFirst { it.id == nextChannel.id }
        if (flatIndex >= 0) playChannel(flatIndex)
    }

    private fun zapPrevious() {
        val current = allChannels.getOrNull(currentIndex) ?: return
        val channelsInCategory = allChannels.filter { it.category == current.category }
        val posInCategory = channelsInCategory.indexOfFirst { it.id == current.id }
        if (posInCategory < 0 || channelsInCategory.isEmpty()) return
        val prevChannel = channelsInCategory[(posInCategory - 1 + channelsInCategory.size) % channelsInCategory.size]
        val flatIndex = allChannels.indexOfFirst { it.id == prevChannel.id }
        if (flatIndex >= 0) playChannel(flatIndex)
    }

    private fun showOsd(channel: Channel) {
        // El número que se ve es la posición del canal dentro de SU país
        // (empieza en 1 en cada país), no el número global guardado en la
        // base de datos. Así, si se borra un canal, los demás se corren
        // solos y no quedan huecos.
        val posInCategory = allChannels.filter { it.category == channel.category }
            .indexOfFirst { it.id == channel.id } + 1
        osdNumber.text = posInCategory.toString()
        osdName.text = channel.name
        ImageLoader.load(lifecycleScope, channel.logoUrl, findViewById(R.id.osdLogo))
        osdContainer.visibility = View.VISIBLE
        osdHideRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { osdContainer.visibility = View.GONE }
        osdHideRunnable = runnable
        handler.postDelayed(runnable, 3000)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (mainSection.visibility != View.VISIBLE) return super.onKeyDown(keyCode, event)

        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP -> { zapNext(); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { zapPrevious(); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE -> {
                if (categoriesColumn.visibility == View.VISIBLE) hideChannelBrowser() else showChannelBrowser()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (categoriesColumn.visibility == View.VISIBLE || channelsList.visibility == View.VISIBLE) {
                    hideChannelBrowser()
                }
                // Antes, si se pulsaba "atrás" mientras solo se estaba
                // viendo un canal (sin el buscador abierto), Android
                // cerraba la aplicación entera sin avisar — esto es lo
                // que pasaba cuando, cambiando de canal con el mando, se
                // rozaba sin querer el botón de atrás. Ahora lo
                // "absorbemos" siempre aquí para que nunca cierre la app
                // sola mientras se está viendo la televisión.
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (categoriesColumn.visibility != View.VISIBLE && channelsList.visibility != View.VISIBLE) {
                    showChannelBrowser()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (categoriesColumn.visibility != View.VISIBLE && channelsList.visibility != View.VISIBLE) {
                    openBrowserAtCurrentChannel()
                    return true
                }
            }
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                val digit = keyCode - KeyEvent.KEYCODE_0
                onDigitEntered(digit)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // Red de seguridad adicional: pase lo que pase con el botón "atrás"
    // del mando, mientras se está en la pantalla principal (viendo la
    // tele) nunca dejamos que cierre la aplicación sola.
    override fun onBackPressed() {
        if (mainSection.visibility == View.VISIBLE) {
            if (categoriesColumn.visibility == View.VISIBLE || channelsList.visibility == View.VISIBLE) {
                hideChannelBrowser()
            }
            return
        }
        super.onBackPressed()
    }

    private fun onDigitEntered(digit: Int) {
        numberBuffer.append(digit)
        osdNumber.text = numberBuffer.toString()
        osdName.text = "Introduce el número de canal…"
        osdContainer.visibility = View.VISIBLE

        numberEntryRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { confirmNumberEntry() }
        numberEntryRunnable = runnable
        handler.postDelayed(runnable, 1500)
    }

    private fun confirmNumberEntry() {
        val typed = numberBuffer.toString().toIntOrNull()
        numberBuffer.clear()
        if (typed == null) return
        // El número tecleado es la posición dentro del país del canal que
        // se está viendo ahora mismo (cada país empieza a contar desde 1),
        // no el número global guardado en la base de datos.
        val currentCategory = allChannels.getOrNull(currentIndex)?.category
        val channelsInCategory = allChannels.filter { it.category == currentCategory }
        val chosen = channelsInCategory.getOrNull(typed - 1)
        val index = if (chosen != null) allChannels.indexOfFirst { it.id == chosen.id } else -1
        if (index >= 0) {
            playChannel(index)
        } else {
            osdName.text = "Canal $typed no encontrado"
            handler.postDelayed({ osdContainer.visibility = View.GONE }, 1500)
        }
    }

    private fun startPresenceHeartbeat() {
        val runnable = object : Runnable {
            override fun run() {
                val currentSession = session ?: return
                val channelId = allChannels.getOrNull(currentIndex)?.id
                lifecycleScope.launch { presenceRepository.ping(currentSession, channelId) }
                handler.postDelayed(this, 20000)
            }
        }
        presenceRunnable = runnable
        handler.post(runnable)
    }

    private fun updatePresenceChannel(channelId: String) {
        val currentSession = session ?: return
        lifecycleScope.launch { presenceRepository.ping(currentSession, channelId) }
    }

    private fun setupAdBanner() {
        val testAdUnitId = "ca-app-pub-3940256099942544/6300978111"
        val adView = AdView(this)
        adView.adUnitId = testAdUnitId
        adView.setAdSize(AdSize.BANNER)
        findViewById<android.widget.FrameLayout>(R.id.adContainer).addView(adView)
        adView.loadAd(AdRequest.Builder().build())
    }

    private fun showOnly(view: View) {
        val allScreens = listOf(
            welcomeSection, loginPanelSection, signUpPanelSection, forgotPanelSection, codeSection, mainSection
        )
        allScreens.forEach { it.visibility = if (it == view) View.VISIBLE else View.GONE }
        view.post {
            when (view) {
                welcomeSection -> findViewById<View>(R.id.welcomeEntrarBtn)?.requestFocus()
                loginPanelSection -> findViewById<View>(R.id.loginEmail)?.requestFocus()
                signUpPanelSection -> findViewById<View>(R.id.signUpEmail)?.requestFocus()
                forgotPanelSection -> findViewById<View>(R.id.forgotEmail)?.requestFocus()
                codeSection -> findViewById<View>(R.id.codeInput)?.requestFocus()
            }
        }
    }

    private fun showError(textView: TextView, message: String) {
        textView.text = message
        textView.visibility = View.VISIBLE
    }

    override fun onStop() {
        super.onStop()
        exoPlayer?.playWhenReady = false
    }

    override fun onStart() {
        super.onStart()
        if (currentIndex in allChannels.indices) {
            playChannel(currentIndex)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        presenceRunnable?.let { handler.removeCallbacks(it) }
        osdHideRunnable?.let { handler.removeCallbacks(it) }
        numberEntryRunnable?.let { handler.removeCallbacks(it) }
        channelRefreshRunnable?.let { handler.removeCallbacks(it) }
        exoPlayer?.release()
    }
}
