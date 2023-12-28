package info.dvkr.screenstream.fragment

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.commitNow
import androidx.lifecycle.lifecycleScope
import com.elvishew.xlog.XLog
import com.google.android.material.radiobutton.MaterialRadioButton
import info.dvkr.screenstream.R
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.module.StreamingModulesManager
import info.dvkr.screenstream.common.view.viewBinding
import info.dvkr.screenstream.databinding.FragmentStreamBinding
import info.dvkr.screenstream.databinding.ItemStreamingModuleBinding
import info.dvkr.screenstream.ui.fragment.AdFragment
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject


public class StreamFragment : AdFragment(R.layout.fragment_stream) {

    private val binding by viewBinding { fragment -> FragmentStreamBinding.bind(fragment.requireView()) }

    private val streamingModulesManager: StreamingModulesManager by inject(mode = LazyThreadSafetyMode.NONE)

    private var job: Job? = null

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
                    if (streamingModulesManager.isActive(module.id)) return@setOnClickListener
                    rbItemStreamingModule.isChecked = false
                    rbItemStreamingModule.setButtonDrawable(R.drawable.ic_radiobox_unchecked_24dp)
                    viewLifecycleOwner.lifecycleScope.launch { streamingModulesManager.selectStreamingModule(module.id) }
                }
                bItemStreamingModuleDetails.contentDescription = module.getContentDescription(requireContext())
                bItemStreamingModuleDetails.setOnClickListener { module.showDescriptionDialog(requireContext(), viewLifecycleOwner) }
            }
        }

        streamingModulesManager.modules.forEach { module ->
            module.isRunning.onEach { isRunning ->
                binding.llFragmentStreamModeItems.findViewWithTag<MaterialRadioButton>(module.id.value)?.apply {
                    isChecked = isRunning
                    setButtonDrawable(if (isRunning) R.drawable.ic_radiobox_checked_24dp else R.drawable.ic_radiobox_unchecked_24dp)
                }
            }.launchIn(viewLifecycleOwner.lifecycleScope)
        }

        loadAdOnViewCreated(binding.flFragmentStreamAdViewContainer)
    }

    override fun onStart() {
        super.onStart()
        XLog.d(getLog("onStart"))
        // Android 6 fix
        job = viewLifecycleOwner.lifecycleScope.launch {
            streamingModulesManager.activeModuleStateFlow.onEach { activeModule ->
                XLog.d(this@StreamFragment.getLog("activeModuleStateFlow.onEach", "${activeModule?.id?.value}"))

                val currentFragment = childFragmentManager.findFragmentById(R.id.fcv_fragment_stream_mode)
                XLog.d(this@StreamFragment.getLog("activeModuleStateFlow.onEach", "Fragment remove: ${currentFragment?.tag}"))
                currentFragment?.let { childFragmentManager.commitNow(allowStateLoss = true) { remove(it) } }

                if (activeModule != null) {
                    XLog.d(this@StreamFragment.getLog("activeModuleStateFlow.onEach", "Fragment add: ${activeModule.id.value}"))
                    childFragmentManager.commitNow(allowStateLoss = true) {
                        add(R.id.fcv_fragment_stream_mode, activeModule.getFragmentClass(), null, activeModule.id.value)
                    }
                }
            }.onCompletion {
                val currentFragment = childFragmentManager.findFragmentById(R.id.fcv_fragment_stream_mode)
                XLog.d(this@StreamFragment.getLog("activeModuleStateFlow.onCompletion", "Fragment remove: ${currentFragment?.tag}"))
                currentFragment?.let { childFragmentManager.commitNow(allowStateLoss = true) { remove(it) } }
            }.collect()
        }
    }

    override fun onStop() {
        XLog.d(getLog("onStop"))
        job?.cancel()
        job = null
        super.onStop()
    }
}