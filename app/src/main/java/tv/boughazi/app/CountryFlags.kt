package tv.boughazi.app

import java.text.Normalizer
import java.util.Locale

/**
 * Bandera para cada país de la lista de categorías. El panel guarda
 * los canales con el nombre del país en español ("Marruecos",
 * "Japón"…), así que aquí se busca ese nombre entre todos los países
 * que conoce Android y se dibuja su bandera. Si la categoría no es un
 * país (por ejemplo "Deportes"), se usa un icono de televisión.
 */
object CountryFlags {

    private const val DEFAULT_ICON = "📺"

    // Algunos nombres que la gente escribe distinto de como los dice Android.
    private val EXTRA_NAMES = mapOf(
        "holanda" to "NL",
        "arabia saudi" to "SA",
        "emiratos" to "AE",
        "emiratos arabes" to "AE",
        "qatar" to "QA",
        "iraq" to "IQ",
        "usa" to "US",
        "eeuu" to "US",
        "inglaterra" to "GB",
    )

    private val codeByName: Map<String, String> by lazy {
        val map = HashMap<String, String>()
        val spanish = Locale("es")
        for (code in Locale.getISOCountries()) {
            val region = Locale("", code)
            for (name in listOf(region.getDisplayCountry(spanish), region.getDisplayCountry(Locale.ENGLISH))) {
                if (name.isNotBlank() && !name.equals(code, ignoreCase = true)) {
                    map.putIfAbsent(normalize(name), code)
                }
            }
        }
        map.putAll(EXTRA_NAMES)
        map
    }

    fun flagFor(category: String): String {
        val code = codeByName[normalize(category)] ?: return DEFAULT_ICON
        return flagFromCode(code)
    }

    private fun flagFromCode(code: String): String {
        val cc = code.uppercase(Locale.ROOT)
        if (cc.length != 2 || !cc.all { it in 'A'..'Z' }) return DEFAULT_ICON
        val sb = StringBuilder()
        for (ch in cc) sb.appendCodePoint(0x1F1E6 + (ch - 'A'))
        return sb.toString()
    }

    private fun normalize(str: String): String =
        Normalizer.normalize(str.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
}
