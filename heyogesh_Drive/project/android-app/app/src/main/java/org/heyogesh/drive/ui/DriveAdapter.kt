package org.heyogesh.drive.ui

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import org.heyogesh.drive.R
import org.heyogesh.drive.api.DriveItem
import org.heyogesh.drive.util.Formatters

/**
 * Supports ordinary browsing, multi-selection and the intentional five-second
 * hold download gesture. The latter is longer than Android's normal long press,
 * so a short long press still enters familiar selection mode.
 */
class DriveAdapter(
    private val onOpen: (DriveItem) -> Unit,
    private val onSelectionChanged: (Int) -> Unit,
    private val onFiveSecondHold: (DriveItem) -> Unit,
) : RecyclerView.Adapter<DriveAdapter.FileHolder>() {
    private val handler = Handler(Looper.getMainLooper())
    private var items: List<DriveItem> = emptyList()
    private val selectedPaths = linkedSetOf<String>()
    var gridMode: Boolean = false
        private set

    fun submit(items: List<DriveItem>) {
        this.items = items
        selectedPaths.retainAll(items.mapTo(mutableSetOf()) { it.path })
        onSelectionChanged(selectedPaths.size)
        notifyDataSetChanged()
    }

    fun setGridMode(enabled: Boolean) {
        if (gridMode == enabled) return
        gridMode = enabled
        notifyDataSetChanged()
    }

    fun selectedItems(): List<DriveItem> = items.filter { it.path in selectedPaths }
    fun clearSelection() {
        if (selectedPaths.isEmpty()) return
        selectedPaths.clear()
        onSelectionChanged(0)
        notifyDataSetChanged()
    }
    fun hasSelection(): Boolean = selectedPaths.isNotEmpty()

    private fun toggle(item: DriveItem) {
        if (!selectedPaths.add(item.path)) selectedPaths.remove(item.path)
        onSelectionChanged(selectedPaths.size)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = if (gridMode) GRID else LIST
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileHolder {
        val layout = if (viewType == GRID) R.layout.item_drive_grid else R.layout.item_drive_list
        return FileHolder(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: FileHolder, position: Int) = holder.bind(items[position])
    override fun onViewRecycled(holder: FileHolder) {
        holder.clearHold()
        super.onViewRecycled(holder)
    }
    override fun getItemCount(): Int = items.size

    inner class FileHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val card = view as MaterialCardView
        private val icon = view.findViewById<ImageView>(R.id.fileIcon)
        private val name = view.findViewById<TextView>(R.id.fileName)
        private val meta = view.findViewById<TextView>(R.id.fileMeta)
        private val check = view.findViewById<MaterialCheckBox>(R.id.selectCheck)
        private var hold: Runnable? = null

        fun bind(item: DriveItem) {
            clearHold()
            name.text = item.name
            meta.text = Formatters.metadata(item)
            icon.setImageResource(Formatters.icon(item))
            val selectionVisible = hasSelection()
            check.visibility = if (selectionVisible) View.VISIBLE else View.GONE
            check.isChecked = item.path in selectedPaths
            card.strokeWidth = if (check.isChecked) 3 else 1
            card.strokeColor = card.context.getColor(if (check.isChecked) R.color.cobalt else R.color.line)
            check.setOnClickListener { toggle(item) }
            card.setOnClickListener {
                if (hasSelection()) toggle(item) else onOpen(item)
            }
            card.setOnLongClickListener {
                toggle(item)
                true
            }
            card.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        hold = Runnable {
                            if (card.isPressed) onFiveSecondHold(item)
                        }.also { handler.postDelayed(it, FIVE_SECONDS) }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> clearHold()
                }
                false
            }
        }

        fun clearHold() {
            hold?.let(handler::removeCallbacks)
            hold = null
        }
    }

    private companion object {
        const val LIST = 0
        const val GRID = 1
        const val FIVE_SECONDS = 5_000L
    }
}
