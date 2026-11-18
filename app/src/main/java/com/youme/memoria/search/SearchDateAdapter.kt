package com.youme.memoria.search

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.card.MaterialCardView
import com.youme.memoria.ImageLoading.ImageUriItem
import com.youme.memoria.R

data class SearchDateGroup(
    val dayKey: String,
    val dateLabel: String,
    val dayTimestamp: Long,
    val bestScore: Float,
    val matchedImages: List<ImageUriItem>,
    val allDayImages: List<ImageUriItem>,
    val isExpanded: Boolean = false
) {
    val matchedUriStrings: Set<String> = matchedImages.mapTo(HashSet()) { it.uri.toString() }
    val extraPhotosCount: Int get() = (allDayImages.size - matchedImages.size).coerceAtLeast(0)
    val canExpand: Boolean get() = extraPhotosCount > 0
    val displayedImages: List<ImageUriItem> get() = if (isExpanded && canExpand) allDayImages else matchedImages
}

class SearchDateAdapter(
    private val onPhotoClick: (Uri) -> Unit,
    private val onToggleExpand: (String) -> Unit
) : ListAdapter<SearchDateGroup, SearchDateAdapter.DayViewHolder>(DIFF_CALLBACK) {

    private val sharedViewPool = RecyclerView.RecycledViewPool().apply {
        setMaxRecycledViews(0, 45)
    }

    class DayViewHolder(
        itemView: View,
        sharedPool: RecyclerView.RecycledViewPool,
        private val onPhotoClick: (Uri) -> Unit,
        private val onToggleExpand: (String) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val headerContainer: View = itemView.findViewById(R.id.dayHeaderContainer)
        private val tvDateTitle: TextView = itemView.findViewById(R.id.tvDateTitle)
        private val tvDateSubtitle: TextView = itemView.findViewById(R.id.tvDateSubtitle)
        private val btnExpandDay: MaterialCardView = itemView.findViewById(R.id.btnExpandDay)
        private val tvExpandLabel: TextView = itemView.findViewById(R.id.tvExpandLabel)
        private val ivExpandArrow: ImageView = itemView.findViewById(R.id.ivExpandArrow)
        private val rvDayPhotos: RecyclerView = itemView.findViewById(R.id.rvDayPhotos)

        private var boundDayKey: String = ""
        private var boundCanExpand: Boolean = false

        private val dayGridAdapter = SearchDayGridAdapter { uri ->
            onPhotoClick(uri)
        }

        init {
            val gridLayoutManager = GridLayoutManager(itemView.context, 3).apply {
                initialPrefetchItemCount = 6
            }
            rvDayPhotos.apply {
                layoutManager = gridLayoutManager
                setRecycledViewPool(sharedPool)
                setHasFixedSize(false)
                isNestedScrollingEnabled = false
                itemAnimator = null
                adapter = dayGridAdapter
            }

            val toggleClickListener = View.OnClickListener {
                if (boundCanExpand && boundDayKey.isNotEmpty()) {
                    onToggleExpand(boundDayKey)
                }
            }
            btnExpandDay.setOnClickListener(toggleClickListener)
            headerContainer.setOnClickListener(toggleClickListener)
        }

        fun bind(group: SearchDateGroup) {
            boundDayKey = group.dayKey
            boundCanExpand = group.canExpand
            headerContainer.isClickable = group.canExpand

            tvDateTitle.text = group.dateLabel

            val matchWord = if (group.matchedImages.size == 1) "1 match" else "${group.matchedImages.size} matches"
            tvDateSubtitle.text = when {
                !group.canExpand -> matchWord
                group.isExpanded -> "All ${group.allDayImages.size} photos from this day · $matchWord highlighted"
                else -> "$matchWord · ${group.allDayImages.size} photos this day"
            }

            btnExpandDay.isVisible = group.canExpand
            if (group.canExpand) {
                if (group.isExpanded) {
                    tvExpandLabel.text = "Matches (${group.matchedImages.size})"
                    ivExpandArrow.rotation = 270f
                } else {
                    tvExpandLabel.text = "See all ${group.allDayImages.size}"
                    ivExpandArrow.rotation = 90f
                }
            }

            dayGridAdapter.submitPhotos(
                newItems = group.displayedImages,
                highlightMatches = group.isExpanded && group.canExpand,
                matchedUris = group.matchedUriStrings
            )
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DayViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.search_date_section_item, parent, false)
        return DayViewHolder(view, sharedViewPool, onPhotoClick, onToggleExpand)
    }

    override fun onBindViewHolder(holder: DayViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(
        holder: DayViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        holder.bind(getItem(position))
    }

    companion object {
        private const val PAYLOAD_EXPANSION = "expansion_changed"

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<SearchDateGroup>() {
            override fun areItemsTheSame(oldItem: SearchDateGroup, newItem: SearchDateGroup): Boolean {
                return oldItem.dayKey == newItem.dayKey
            }

            override fun areContentsTheSame(oldItem: SearchDateGroup, newItem: SearchDateGroup): Boolean {
                return oldItem.isExpanded == newItem.isExpanded &&
                    oldItem.dateLabel == newItem.dateLabel &&
                    oldItem.matchedImages == newItem.matchedImages &&
                    oldItem.allDayImages.size == newItem.allDayImages.size
            }

            override fun getChangePayload(oldItem: SearchDateGroup, newItem: SearchDateGroup): Any? {
                return if (
                    oldItem.dayKey == newItem.dayKey &&
                    oldItem.matchedImages == newItem.matchedImages &&
                    oldItem.allDayImages.size == newItem.allDayImages.size &&
                    oldItem.isExpanded != newItem.isExpanded
                ) {
                    PAYLOAD_EXPANSION
                } else {
                    null
                }
            }
        }
    }
}

private class SearchDayGridAdapter(
    private val onItemClick: (Uri) -> Unit
) : RecyclerView.Adapter<SearchDayGridAdapter.PhotoVH>() {

    private var items: List<ImageUriItem> = emptyList()
    private var highlightMatches: Boolean = false
    private var matchedUris: Set<String> = emptySet()

    class PhotoVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageView: ImageView = itemView.findViewById(R.id.imageView13)
        val checkMark: ImageView = itemView.findViewById(R.id.imageView14)
        val box: MaterialCardView = itemView.findViewById(R.id.box)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.album_view_item, parent, false)
        return PhotoVH(view)
    }

    override fun onBindViewHolder(holder: PhotoVH, position: Int) {
        val item = items[position]
        val metrics = holder.itemView.resources.displayMetrics
        val thumbSize = (metrics.widthPixels / 3).coerceAtLeast(1)

        Glide.with(holder.imageView)
            .load(item.uri)
            .override(thumbSize, thumbSize)
            .centerCrop()
            .format(DecodeFormat.PREFER_RGB_565)
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .error(R.drawable.baseline_broken_image_24)
            .into(holder.imageView)

        val isHighlightedMatch = highlightMatches && item.uri.toString() in matchedUris
        holder.checkMark.isVisible = false
        holder.box.scaleX = 1f
        holder.box.scaleY = 1f
        if (isHighlightedMatch) {
            val strokePx = (2.5f * metrics.density).toInt().coerceAtLeast(3)
            holder.box.strokeWidth = strokePx
            holder.box.strokeColor = ContextCompat.getColor(holder.itemView.context, R.color.primary_80)
        } else {
            holder.box.strokeWidth = 0
        }

        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION && pos < items.size) {
                onItemClick(items[pos].uri)
            }
        }
    }

    override fun onViewRecycled(holder: PhotoVH) {
        super.onViewRecycled(holder)
        Glide.with(holder.imageView).clear(holder.imageView)
    }

    override fun getItemCount(): Int = items.size

    fun submitPhotos(
        newItems: List<ImageUriItem>,
        highlightMatches: Boolean,
        matchedUris: Set<String>
    ) {
        val oldItems = this.items
        val oldHighlight = this.highlightMatches
        val oldMatched = this.matchedUris

        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldItems.size
            override fun getNewListSize(): Int = newItems.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldItems[oldItemPosition].uri == newItems[newItemPosition].uri
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldUri = oldItems[oldItemPosition].uri.toString()
                val newUri = newItems[newItemPosition].uri.toString()
                val oldWasHighlighted = oldHighlight && oldUri in oldMatched
                val newIsHighlighted = highlightMatches && newUri in matchedUris
                return oldUri == newUri && oldWasHighlighted == newIsHighlighted
            }
        })

        this.items = newItems
        this.highlightMatches = highlightMatches
        this.matchedUris = matchedUris
        diff.dispatchUpdatesTo(this)
    }
}
