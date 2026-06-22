package jp.co.sstinc.lsimpledev

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import jp.co.sstinc.lsimpledev.databinding.ItemReceiveHistoryBinding

class ReceiveHistoryAdapter :
    ListAdapter<ReceiveHistoryItem, ReceiveHistoryAdapter.ViewHolder>(DiffCallback) {

    private var lastAnimatedItemId: Long = Long.MIN_VALUE

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemReceiveHistoryBinding.inflate(inflater, parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item)

        holder.itemView.animate().cancel()
        holder.itemView.alpha = 1f
        holder.itemView.translationY = 0f

        if (position == 0 && item.id > lastAnimatedItemId) {
            lastAnimatedItemId = item.id
            holder.itemView.alpha = 0f
            holder.itemView.animate()
                .alpha(1f)
                .setDuration(180L)
                .start()
        }
    }

    class ViewHolder(
        private val binding: ItemReceiveHistoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ReceiveHistoryItem) {
            binding.historyTimestampText.text = item.timestamp
            binding.historyByteSizeText.text = item.byteSizeText
            binding.historyRawText.text = item.rawDataText

            if (item.showProcessed) {
                binding.historyProcessedText.visibility = View.VISIBLE
                binding.historyProcessedText.text =
                    if (item.processedTitle.isNotEmpty()) {
                        item.processedTitle + ": " + item.processedDataText
                    } else {
                        item.processedDataText
                    }
            } else {
                binding.historyProcessedText.visibility = View.GONE
                binding.historyProcessedText.text = ""
            }
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<ReceiveHistoryItem>() {
            override fun areItemsTheSame(
                oldItem: ReceiveHistoryItem,
                newItem: ReceiveHistoryItem
            ): Boolean = oldItem.id == newItem.id

            override fun areContentsTheSame(
                oldItem: ReceiveHistoryItem,
                newItem: ReceiveHistoryItem
            ): Boolean = oldItem == newItem
        }
    }
}