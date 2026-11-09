package tv.boughazi.app

/** Una película de la tabla "peliculas" de Supabase. */
data class Movie(
    val id: Long,
    val title: String,
    val description: String,
    val category: String,
    val year: Int?,
    val posterUrl: String?,
    val videoUrl: String
)
