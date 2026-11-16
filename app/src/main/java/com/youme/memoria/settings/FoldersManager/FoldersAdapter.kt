package com.youme.memoria.settings.FoldersManager

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.youme.memoria.Gallery.FolderInfo
import com.youme.memoria.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class FoldersAdapter(val lifecycle: LifecycleCoroutineScope) : RecyclerView.Adapter<FoldersAdapter.MyViewHolder>() {

    private var items: List<FolderInfo> = emptyList()
    private var filteredItems: List<FolderInfo> = emptyList()
    private var currentQuery: String = ""

    private val _selectedFolders = MutableStateFlow<Set<String>>(emptySet())
    val selectedFoldersFlow: StateFlow<Set<String>> = _selectedFolders.asStateFlow()

    class MyViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.textView26)
        val photosCount: TextView = itemView.findViewById(R.id.textView27)
        val folderPath: TextView = itemView.findViewById(R.id.textView24)
        val recyclerView: RecyclerView = itemView.findViewById(R.id.recyclerView2)
        val checkBox: MaterialCheckBox = itemView.findViewById(R.id.checkBox)
        val box: MaterialCardView = itemView.findViewById(R.id.box)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.folders_box_item, parent, false)
        return MyViewHolder(view)
    }

    override fun onBindViewHolder(holder: MyViewHolder, position: Int) {
        val folder = filteredItems[position]
        val isSelected = _selectedFolders.value.contains(folder.bucketId)

        holder.folderPath.text = folder.folderPath
        holder.title.text = folder.bucketName
        holder.photosCount.text = "${folder.itemCount} photo"
        holder.checkBox.isChecked = isSelected

        holder.box.setCardBackgroundColor(
            if (isSelected) "#4ED0BCFF".toColorInt()
            else holder.itemView.resources.getColor(R.color.neutral_17)
        )

        holder.box.setOnClickListener {
            val nowChecked = !holder.checkBox.isChecked
            holder.checkBox.isChecked = nowChecked

            _selectedFolders.update { currentSet ->
                if (nowChecked) currentSet + folder.bucketId else currentSet - folder.bucketId
            }
            notifyItemChanged(position)
        }
    }

    fun setAllSelected() {
        if (currentQuery.isNotEmpty()) {
            _selectedFolders.update { it + filteredItems.map { f -> f.bucketId } }
        } else {
            _selectedFolders.value = items.map { it.bucketId }.toSet()
        }
        notifyDataSetChanged()
    }

    fun setAllDeselected() {
        if (currentQuery.isNotEmpty()) {
            val visibleIds = filteredItems.map { it.bucketId }.toSet()
            _selectedFolders.update { it - visibleIds }
        } else {
            _selectedFolders.value = emptySet()
        }
        notifyDataSetChanged()
    }

    fun setSelected(ids: Set<String>) {
        _selectedFolders.value = ids
        notifyDataSetChanged()
    }

    fun search(query: String) {
        currentQuery = query.trim()
        applyFilter()
        notifyDataSetChanged()
    }

    fun submitData(folders: List<FolderInfo>) {
        items = folders
        applyFilter()
        notifyDataSetChanged()
    }

    private fun applyFilter() {
        filteredItems = if (currentQuery.isNotEmpty()) {
            items.filter {
                it.bucketName.contains(currentQuery, ignoreCase = true) ||
                    it.folderPath.contains(currentQuery, ignoreCase = true)
            }
        } else {
            items
        }
    }

    override fun getItemCount(): Int {
        return filteredItems.size
    }
}
