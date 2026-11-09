package tv.boughazi.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope

/** Cuadrícula de carteles estilo Netflix: póster alto y el nombre debajo. */
class MovieAdapter(
    private val scope: CoroutineScope,
    private val onClick: (Movie) -> Unit
) : RecyclerView.Adapter<MovieAdapter.VH>() {

    private var items: List<Movie> = emptyList()

    fun submit(list: List<Movie>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val poster: ImageView = v.findViewById(R.id.movie_poster)
        val placeholder: TextView = v.findViewById(R.id.movie_placeholder)
        val title: TextView = v.findViewById(R.id.movie_title)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_movie, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        holder.title.text = m.title
        holder.placeholder.text = m.title
        // Sin póster: se enseña el título sobre un fondo oscuro.
        holder.placeholder.visibility =
            if (m.posterUrl.isNullOrBlank()) View.VISIBLE else View.GONE
        ImageLoader.load(scope, m.posterUrl, holder.poster)
        holder.itemView.setOnClickListener { onClick(m) }
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            val s = if (hasFocus) 1.06f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(120).start()
        }
    }

    override fun getItemCount(): Int = items.size
}
