package com.youme.memoria.Gallery

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.youme.memoria.Album.AlbumViewer.AlbumViewerAdapter
import com.youme.memoria.R
import com.youme.memoria.roomCach.AlbumPhotoEntity

class GalleryDateAdapter(private val onClick: (Uri) -> Unit) :
    PagingDataAdapter<DateImageBlocks, GalleryDateAdapter.MyViewHolder>(DIFF_CALLBACK) {

    private val sharedViewPool = RecyclerView.RecycledViewPool().apply {
        setMaxRecycledViews(0, 36)
    }

    class MyViewHolder(
        itemView: View,
        sharedPool: RecyclerView.RecycledViewPool,
        onItemTapped: (Uri) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        val recyclerView: RecyclerView = itemView.findViewById(R.id.rec)
        val date: TextView = itemView.findViewById(R.id.textView17)
        var currentImages: List<Uri> = emptyList()

        val childAdapter = AlbumViewerAdapter(
            emptyList(),
            { _, _ -> },
            { index ->
                currentImages.getOrNull(index)?.let(onItemTapped)
            },
            false
        )

        init {
            val gridLayoutManager = GridLayoutManager(itemView.context, 3).apply {
                initialPrefetchItemCount = 6
            }
            recyclerView.apply {
                layoutManager = gridLayoutManager
                setRecycledViewPool(sharedPool)
                setHasFixedSize(false)
                itemAnimator = null
                adapter = childAdapter
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.data_image_items, parent, false)
        return MyViewHolder(view, sharedViewPool, onClick)
    }

    override fun onBindViewHolder(holder: MyViewHolder, position: Int) {
        val dateBlock = getItem(position) ?: return
        holder.date.text = dateBlock.dataTaken
        holder.currentImages = dateBlock.images.map { it.uri }
        holder.childAdapter.submitData(
            dateBlock.images.map {
                AlbumPhotoEntity("", it.uri.toString())
            }
        )
    }

    companion object {
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<DateImageBlocks>() {
                override fun areItemsTheSame(
                    oldItem: DateImageBlocks,
                    newItem: DateImageBlocks
                ): Boolean {
                    return oldItem.dataTaken == newItem.dataTaken
                }

                override fun areContentsTheSame(
                    oldItem: DateImageBlocks,
                    newItem: DateImageBlocks
                ): Boolean {
                    return oldItem == newItem
                }
            }
    }
    }
