package info.dvkr.screenstream.fragment

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.elvishew.xlog.XLog
import com.google.android.material.radiobutton.MaterialRadioButton
import info.dvkr.screenstream.R
import info.dvkr.screenstream.common.StreamingModulesManager
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.view.viewBinding
import info.dvkr.screenstream.databinding.FragmentStreamBinding
import info.dvkr.screenstream.databinding.ItemStreamingModuleBinding
import info.dvkr.screenstream.settings.AppSettings
import info.dvkr.screenstream.ui.fragment.AdFragment
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject


public class StreamFragment : AdFragment(R.layout.fragment_stream) {

    private val binding by viewBinding { fragment -> FragmentStreamBinding.bind(fragment.requireView()) }

    private val streamingModulesManager: StreamingModulesManager by inject(mode = LazyThreadSafetyMode.NONE)
    private val appSettings: AppSettings by inject(mode = LazyThreadSafetyMode.NONE)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        XLog.d(getLog("onViewCreated"))
        binding.llFragmentStreamMode.isVisible = streamingModulesManager.modules.isNotEmpty()

        streamingModulesManager.modules.forEach { module ->
            ItemStreamingModuleBinding.inflate(layoutInflater, binding.llFragmentStreamModeItems, true).apply {
                rbItemStreamingModule.tag = module.id.value
                rbItemStreamingModule.text = module.getName(requireContext())
                rbItemStreamingModule.isChecked = false
                rbItemStreamingModule.setButtonDrawable(R.drawable.ic_radiobox_unchecked_24dp)
                rbItemStreamingModule.setOnClickListener {
                    if (streamingModulesManager.activeModuleStateFlow.value?.id == module.id) return@setOnClickListener
                    rbItemStreamingModule.isChecked = false
                    rbItemStreamingModule.setButtonDrawable(R.drawable.ic_radiobox_unchecked_24dp)
                    streamingModulesManager.modules.forEach { module ->
                        binding.llFragmentStreamModeItems.findViewWithTag<MaterialRadioButton>(module.id.value).isEnabled = false
                    }
                    viewLifecycleOwner.lifecycleScope.launch { appSettings.setStreamingModule(module.id) }
                }
                bItemStreamingModuleDetails.contentDescription = module.getContentDescription(requireContext())
                bItemStreamingModuleDetails.setOnClickListener { module.showDescriptionDialog(requireContext(), viewLifecycleOwner) }
            }
        }

        streamingModulesManager.modules.forEach { module ->
            module.streamingServiceIsActive.onEach { isServiceActive ->
                binding.llFragmentStreamModeItems.findViewWithTag<MaterialRadioButton>(module.id.value).apply {
                    isChecked = isServiceActive
                    setButtonDrawable(if (isServiceActive) R.drawable.ic_radiobox_checked_24dp else R.drawable.ic_radiobox_unchecked_24dp)
                }
                if (isServiceActive)
                    streamingModulesManager.modules.forEach { module ->
                        binding.llFragmentStreamModeItems.findViewWithTag<MaterialRadioButton>(module.id.value).isEnabled = true
                    }
            }.launchIn(viewLifecycleOwner.lifecycleScope)
        }

        streamingModulesManager.activeModuleStateFlow
            .filterNotNull()
            .filter { it.id.value != childFragmentManager.findFragmentById(R.id.fcv_fragment_stream_mode)?.tag }
            .onEach { activeModule ->
                XLog.d(getLog("onViewCreated","Fragment replace from : ${childFragmentManager.findFragmentById(R.id.fcv_fragment_stream_mode)?.tag} to ${activeModule.id.value}"))
                childFragmentManager.beginTransaction()
                    .replace(R.id.fcv_fragment_stream_mode, activeModule.getFragmentClass(), null, activeModule.id.value)
                    .commitAllowingStateLoss()
            }.launchIn(viewLifecycleOwner.lifecycleScope)

        loadAdOnViewCreated(binding.flFragmentStreamAdViewContainer)
    }

    override fun onDestroyView() {
        XLog.d(getLog("onDestroyView"))
        super.onDestroyView()
    }
}