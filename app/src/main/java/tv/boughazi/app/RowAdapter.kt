package tv.boughazi.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Fila simple: icono/bandera opcional, título y subtítulo opcional.
 * Se usa tanto para la lista de categorías como para la lista de
 * canales dentro de una categoría.
 */
data class RowItem(
    val flag: String = "",
    val title: String,
    val subtitle: String = ""
)

class RowAdapter(
    private var items: List<RowItem>,
    private val onLongSelect: ((Int) -> Unit)? = null,
    private val onSelect: (Int) -> Unit
) : RecyclerView.Adapter<RowAdapter.RowViewHolder>() {

    class RowViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val flag: TextView = view.findViewById(R.id.row_flag)
        val title: TextView = view.findViewById(R.id.row_title)
        val subtitle: TextView = view.findViewById(R.id.row_subtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false)
        return RowViewHolder(view)
    }

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        val item = items[position]
        holder.flag.text = item.flag
        holder.title.text = item.title
        holder.subtitle.text = item.subtitle
        holder.itemView.setOnClickListener { onSelect(position) }
        val longCallback = onLongSelect
        if (longCallback != null) {
            // Con pulsación larga: Android distingue solo entre OK (clic)
            // y OK mantenido (pulsación larga), tanto con mando como al tocar.
            holder.itemView.setOnKeyListener(null)
            holder.itemView.setOnLongClickListener {
                longCallback(position)
                true
            }
        } else {
            holder.itemView.setOnLongClickListener(null)
            holder.itemView.isLongClickable = false
            holder.itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                    (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER)
                ) {
                    onSelect(position)
                    true
                } else {
                    false
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    /** Cambia solo una fila (por ejemplo, para poner o quitar la estrella). */
    fun updateItem(position: Int, item: RowItem) {
        if (position !in items.indices) return
        items = items.toMutableList().also { it[position] = item }
        notifyItemChanged(position, "fav")
    }

    fun updateItems(newItems: List<RowItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
