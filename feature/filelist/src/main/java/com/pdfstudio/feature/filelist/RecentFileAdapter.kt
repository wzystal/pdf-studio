package com.pdfstudio.feature.filelist

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import android.net.Uri
import com.pdfstudio.core.common.io.UriDisplayNameResolver
import com.pdfstudio.core.storage.entity.RecentFileEntity
import com.pdfstudio.feature.filelist.databinding.ItemRecentFileBinding
import java.text.DateFormat
import java.util.Date

class RecentFileAdapter(
    private val onClick: (RecentFileEntity) -> Unit,
) : ListAdapter<RecentFileEntity, RecentFileAdapter.ViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecentFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemRecentFileBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: RecentFileEntity) {
            binding.tvName.text = UriDisplayNameResolver.resolve(
                binding.root.context,
                Uri.parse(item.uri),
                item.displayName,
            )
            val date = DateFormat.getDateTimeInstance().format(Date(item.lastOpenedAt))
            binding.tvMeta.text = "${item.pageCount} pages · $date"
            binding.root.setOnClickListener { onClick(item) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<RecentFileEntity>() {
        override fun areItemsTheSame(oldItem: RecentFileEntity, newItem: RecentFileEntity) =
            oldItem.uri == newItem.uri

        override fun areContentsTheSame(oldItem: RecentFileEntity, newItem: RecentFileEntity) =
            oldItem == newItem
    }
}
