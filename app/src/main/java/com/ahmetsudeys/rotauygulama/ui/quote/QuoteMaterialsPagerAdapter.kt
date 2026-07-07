package com.ahmetsudeys.rotauygulama.ui.quote

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

class QuoteMaterialsPagerAdapter(
    fragment: Fragment,
    private val operations: List<String>
) : FragmentStateAdapter(fragment) {

    override fun getItemCount(): Int = operations.size

    override fun createFragment(position: Int): Fragment {
        return QuoteMaterialsPageFragment.newInstance(operations[position])
    }
}


