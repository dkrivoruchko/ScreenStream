package info.dvkr.screenstream.fragment

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.BaseApp
import info.dvkr.screenstream.R
import info.dvkr.screenstream.common.getAppVersion
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.view.viewBinding
import info.dvkr.screenstream.databinding.FragmentAboutBinding
import info.dvkr.screenstream.ui.activity.AdActivity

public class AboutFragment : Fragment(R.layout.fragment_about) {

    private val binding by viewBinding { fragment -> FragmentAboutBinding.bind(fragment.requireView()) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvFragmentAboutVersion.text = getString(R.string.app_about_fragment_app_version, requireContext().getAppVersion())

        var settingsLoggingVisibleCounter = 0
        binding.tvFragmentAboutVersion.setOnClickListener {
            if ((requireActivity().application as BaseApp).isLoggingOn) return@setOnClickListener
            settingsLoggingVisibleCounter++
            if (settingsLoggingVisibleCounter >= 5) {
                (requireActivity().application as BaseApp).isLoggingOn = true
                Toast.makeText(requireContext(), "Logging option enabled", Toast.LENGTH_LONG).show()
                binding.tvFragmentAboutVersion.setOnClickListener(null)
            }
        }

        binding.bFragmentAboutRate.setOnClickListener {
            val packageName = it.context.packageName
            openStringUrl("market://details?id=$packageName") {
                openStringUrl("https://play.google.com/store/apps/details?id=$packageName")
            }
        }

        binding.bFragmentAboutSources.setOnClickListener {
            openStringUrl("https://github.com/dkrivoruchko/ScreenStream")
        }

        binding.bFragmentTermsConditions.setOnClickListener {
            openStringUrl("https://github.com/dkrivoruchko/ScreenStream/blob/master/TermsConditions.md")
        }

        binding.bFragmentPrivacyPolicy.setOnClickListener {
            openStringUrl("https://github.com/dkrivoruchko/ScreenStream/blob/master/PrivacyPolicy.md")
        }

        if ((requireActivity() as AdActivity).isPrivacyOptionsRequired) {
            binding.bFragmentPrivacyOptions.setOnClickListener { (requireActivity() as AdActivity).showPrivacyOptionsForm() }
        } else {
            binding.bFragmentPrivacyOptions.visibility = View.GONE
        }

        binding.bFragmentLicense.setOnClickListener {
            openStringUrl("https://github.com/dkrivoruchko/ScreenStream/blob/master/LICENSE")
        }
    }

    private fun openStringUrl(url: String, onFailure: () -> Unit = {}) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            XLog.w(getLog("openStringUrl", url))
            runCatching { onFailure.invoke() }
        }
    }
}