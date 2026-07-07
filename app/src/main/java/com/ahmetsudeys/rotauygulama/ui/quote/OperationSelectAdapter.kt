package com.ahmetsudeys.rotauygulama.ui.quote

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ahmetsudeys.rotauygulama.databinding.ItemOperationCheckboxBinding

class OperationSelectAdapter(
    private val items: List<String>,
    private val selected: MutableSet<String>,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<OperationSelectAdapter.Vh>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val inflater = LayoutInflater.from(parent.context)
        return Vh(ItemOperationCheckboxBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: Vh, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class Vh(
        private val binding: ItemOperationCheckboxBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(name: String) {
            binding.checkboxOperation.text = name
            binding.checkboxOperation.setOnCheckedChangeListener(null)
            binding.checkboxOperation.isChecked = selected.contains(name)
            binding.checkboxOperation.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selected.add(name)
                } else {
                    selected.remove(name)
                }
                onSelectionChanged()
            }
        }
    }
}


