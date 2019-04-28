package info.dvkr.screenstream.ui.fragment

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.other.getLog
import kotlinx.android.synthetic.main.fragment_settings.*

class SettingsFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tl_fragment_settings.setupWithViewPager(vp_fragment_settings)
        vp_fragment_settings.adapter = SettingsPageAdapter(requireContext(), childFragmentManager)
    }

    override fun onStart() {
        super.onStart()
        XLog.d(getLog("onStart", "Invoked"))
    }

    override fun onStop() {
        XLog.d(getLog("onStop", "Invoked"))
        super.onStop()
    }

    internal class SettingsPageAdapter(
        val context: Context,
        fragmentManager: FragmentManager
    ) : FragmentPagerAdapter(fragmentManager) {

        override fun getCount(): Int = 4

        override fun getItem(position: Int): Fragment =
            when (position) {
                0 -> SettingsInterfaceFragment()
                1 -> SettingsImageFragment()
                2 -> SettingsSecturityFragment()
                3 -> SettingsAdvancedFragment()
                else -> throw IllegalArgumentException("SettingsPageAdapter.getItem: unexpected position: $position")
            }

        override fun getPageTitle(position: Int): CharSequence =
            when (position) {
                0 -> context.getString(R.string.pref_settings_interface)
                1 -> context.getString(R.string.pref_settings_image)
                2 -> context.getString(R.string.pref_settings_security)
                3 -> context.getString(R.string.pref_settings_advanced)
                else -> throw IllegalArgumentException("SettingsPageAdapter.getPageTitle: unexpected position: $position")
            }
    }
}