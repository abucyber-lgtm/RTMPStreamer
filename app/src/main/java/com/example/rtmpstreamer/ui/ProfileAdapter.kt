package com.example.rtmpstreamer.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rtmpstreamer.data.StreamProfile
import com.example.rtmpstreamer.databinding.ItemProfileBinding

class ProfileAdapter(
    private val onToggle: (StreamProfile, Boolean) -> Unit,
    private val onDelete: (StreamProfile) -> Unit
) : ListAdapter<StreamProfile, ProfileAdapter.ProfileViewHolder>(DIFF) {

    // id profil yang sedang aktif terhubung (untuk indikator titik hijau + status teks)
    var activeConnections: Set<Long> = emptySet()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder {
        val binding = ItemProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ProfileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ProfileViewHolder(private val binding: ItemProfileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(profile: StreamProfile) {
            binding.tvProfileLabel.text = profile.label
            binding.tvProfileUrl.text = profile.url

            binding.switchEnabled.setOnCheckedChangeListener(null)
            binding.switchEnabled.isChecked = profile.enabled
            binding.switchEnabled.setOnCheckedChangeListener { _, checked ->
                onToggle(profile, checked)
            }

            binding.btnDeleteProfile.setOnClickListener { onDelete(profile) }

            val isLive = activeConnections.contains(profile.id)
            binding.viewLiveDot.visibility = if (isLive) android.view.View.VISIBLE else android.view.View.INVISIBLE
            binding.tvProfileConnStatus.visibility = if (isLive) android.view.View.VISIBLE else android.view.View.GONE
            binding.tvProfileConnStatus.text = "Terhubung"
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<StreamProfile>() {
            override fun areItemsTheSame(oldItem: StreamProfile, newItem: StreamProfile) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: StreamProfile, newItem: StreamProfile) =
                oldItem == newItem
        }
    }
}
