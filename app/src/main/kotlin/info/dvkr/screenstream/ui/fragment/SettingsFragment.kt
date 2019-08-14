package info.dvkr.screenstream.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.elvishew.xlog.XLog
import com.google.android.material.tabs.TabLayoutMediator
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.other.getLog
import kotlinx.android.synthetic.main.fragment_settings.*

class SettingsFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        vp_fragment_settings.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 4

            override fun createFragment(position: Int): Fragment =
                when (position) {
                    0 -> SettingsInterfaceFragment()
                    1 -> SettingsImageFragment()
                    2 -> SettingsSecturityFragment()
                    3 -> SettingsAdvancedFragment()
                    else -> throw IllegalArgumentException("FragmentStateAdapter.getItem: unexpected position: $position")
                }
        }

        TabLayoutMediator(tl_fragment_settings, vp_fragment_settings) { tab, position ->
            tab.text = when (position) {
                0 -> requireContext().getString(R.string.pref_settings_interface)
                1 -> requireContext().getString(R.string.pref_settings_image)
                2 -> requireContext().getString(R.string.pref_settings_security)
                3 -> requireContext().getString(R.string.pref_settings_advanced)
                else -> throw IllegalArgumentException("TabLayoutMediator: unexpected position: $position")
            }
        }.attach()
    }

    override fun onStart() {
        super.onStart()
        XLog.d(getLog("onStart", "Invoked"))
    }

    override fun onStop() {
        XLog.d(getLog("onStop", "Invoked"))
        super.onStop()
    }
}