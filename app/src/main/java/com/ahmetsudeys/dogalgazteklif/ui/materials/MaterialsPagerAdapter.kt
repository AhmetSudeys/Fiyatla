package com.ahmetsudeys.dogalgazteklif.ui.materials

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

class MaterialsPagerAdapter(
    fragment: Fragment,
    private val sheetNames: List<String>
) : FragmentStateAdapter(fragment) {

    override fun getItemCount(): Int = sheetNames.size

    override fun createFragment(position: Int): Fragment {
        return MaterialsPageFragment.newInstance(sheetNames[position])
    }
}


