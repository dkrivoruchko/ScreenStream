package info.dvkr.screenstream.webrtc.ui

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import info.dvkr.screenstream.common.view.viewBinding
import info.dvkr.screenstream.webrtc.R
import info.dvkr.screenstream.webrtc.WebRtcKoinQualifier
import info.dvkr.screenstream.webrtc.WebRtcSettings
import info.dvkr.screenstream.webrtc.WebRtcStreamingModule
import info.dvkr.screenstream.webrtc.databinding.FragmentWebrtcSettingsBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.core.qualifier.named

public class WebRtcSettingsFragment : BottomSheetDialogFragment(R.layout.fragment_webrtc_settings) {

    internal companion object {
        internal const val TAG = "info.dvkr.screenstream.webrtc.ui.WebRtcSettingsFragment"
    }

    private val binding by viewBinding { fragment -> FragmentWebrtcSettingsBinding.bind(fragment.requireView()) }

    private val mjpegStreamingModule: WebRtcStreamingModule by inject(named(WebRtcKoinQualifier), LazyThreadSafetyMode.NONE)
    private val webRtcSettings: WebRtcSettings by mjpegStreamingModule.scope.inject(mode = LazyThreadSafetyMode.NONE)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.bFragmentSettingsClose.setOnClickListener { dismissAllowingStateLoss() }

        // Interface - Keep awake
        if (Build.MANUFACTURER !in listOf("OnePlus", "OPPO")) {
            viewLifecycleOwner.lifecycleScope.launch {
                binding.cbFragmentSettingsKeepAwake.isChecked = webRtcSettings.keepAwakeFlow.first()
            }
            binding.cbFragmentSettingsKeepAwake.setOnClickListener {
                viewLifecycleOwner.lifecycleScope.launch {
                    webRtcSettings.setKeepAwake(binding.cbFragmentSettingsKeepAwake.isChecked)
                }
            }
            binding.clFragmentSettingsKeepAwake.setOnClickListener { binding.cbFragmentSettingsKeepAwake.performClick() }
        } else {
            binding.clFragmentSettingsKeepAwake.visibility = View.GONE
            binding.vFragmentSettingsKeepAwake.visibility = View.GONE
        }

        // Interface - Stop on sleep
        viewLifecycleOwner.lifecycleScope.launch {
            binding.cbFragmentSettingsStopOnSleep.isChecked = webRtcSettings.stopOnSleepFlow.first()
        }
        binding.cbFragmentSettingsStopOnSleep.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                webRtcSettings.setStopOnSleep(binding.cbFragmentSettingsStopOnSleep.isChecked)
            }
        }
        binding.clFragmentSettingsStopOnSleep.setOnClickListener { binding.cbFragmentSettingsStopOnSleep.performClick() }
    }
}