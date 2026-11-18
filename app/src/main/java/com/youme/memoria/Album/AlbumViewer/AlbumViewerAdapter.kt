package com.youme.memoria.Album.AlbumViewer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.card.MaterialCardView
import com.youme.memoria.R
import com.youme.memoria.roomCach.AlbumPhotoEntity

class AlbumViewerAdapter(
    private var items: List<AlbumPhotoEntity>,
    private var onItemSelected: (selected: MutableList<String>, photos: List<AlbumPhotoEntity>) -> Unit,
    private var onItemClick: (position: Int) -> Unit,
    private var enableSelection: Boolean = true,
) : RecyclerView.Adapter<AlbumViewerAdapter.MyViewHolder>() {

    val selected = mutableListOf<String>()

    class MyViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageView: ImageView = itemView.findViewById(R.id.imageView13)
        val checkMark: ImageView = itemView.findViewById(R.id.imageView14)
        val box: MaterialCardView = itemView.findViewById(R.id.box)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.album_view_item, parent, false)
        return MyViewHolder(view)
    }

    override fun onBindViewHolder(holder: MyViewHolder, position: Int) {
        val photo = items[position]
        val thumbSize = (holder.itemView.resources.displayMetrics.widthPixels / 3).coerceAtLeast(1)

        Glide.with(holder.imageView)
            .load(photo.uri)
            .override(thumbSize, thumbSize)
            .centerCrop()
            .format(DecodeFormat.PREFER_RGB_565)
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .error(R.drawable.baseline_broken_image_24)
            .into(holder.imageView)

        val isSelected = selected.contains(photo.uri)
        holder.checkMark.isVisible = isSelected

        holder.box.strokeWidth = if (isSelected) 10 else 0
        holder.box.apply {
            if (isSelected) {
                scaleX = 0.9f
                scaleY = 0.9f
            } else {
                scaleX = 1f
                scaleY = 1f
            }
        }

        holder.itemView.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION && selected.isEmpty() && enableSelection) {
                selected.add(items[pos].uri)
                onItemSelected(selected, items)
                notifyItemChanged(pos)
            }
            true
        }
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            val uri = items[pos].uri
            if (selected.isNotEmpty()) {
                if (selected.contains(uri)) selected.remove(uri) else selected.add(uri)
                onItemSelected(selected, items)
                notifyItemChanged(pos)
            } else {
                onItemClick(pos)
            }
        }
    }

    fun selectAll() {
        selected.clear()
        selected.addAll(items.map { it.uri })
        onItemSelected(selected, items)
        notifyDataSetChanged()
    }

    fun removeSelected() {
        selected.clear()
        onItemSelected(selected, items)
        notifyDataSetChanged()
    }

    fun deselectAll() {
        selected.clear()
        onItemSelected(selected, items)
        notifyDataSetChanged()
    }

    fun submitData(albums: List<AlbumPhotoEntity>) {
        val oldList = items
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldList.size
            override fun getNewListSize(): Int = albums.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                oldList[oldPos].uri == albums[newPos].uri
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                oldList[oldPos] == albums[newPos]
        })
        items = albums
        diff.dispatchUpdatesTo(this)
    }

    override fun onViewRecycled(holder: MyViewHolder) {
        super.onViewRecycled(holder)
        Glide.with(holder.imageView).clear(holder.imageView)
    }

    override fun getItemCount(): Int {
        return items.size
    }
}
