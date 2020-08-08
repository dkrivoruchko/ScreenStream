package info.dvkr.screenstream.ui.fragment

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.lifecycle.lifecycleOwner
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.data.settings.Settings
import info.dvkr.screenstream.databinding.FragmentAboutBinding
import info.dvkr.screenstream.ui.viewBinding
import org.koin.android.ext.android.inject

class AboutFragment : Fragment(R.layout.fragment_about) {

    private val settings: Settings by inject()
    private var settingsLoggingVisibleCounter: Int = 0
    private var version: String = ""

    private val binding by viewBinding { fragment -> FragmentAboutBinding.bind(fragment.requireView()) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        with(requireActivity()) {
            try {
                version = packageManager.getPackageInfo(packageName, 0).versionName
                binding.tvFragmentAboutVersion.text = getString(R.string.about_fragment_app_version, version)
            } catch (t: Throwable) {
                XLog.e(getLog("onViewCreated", "getPackageInfo"), t)
            }

            binding.tvFragmentAboutVersion.setOnClickListener {
                settingsLoggingVisibleCounter++
                if (settingsLoggingVisibleCounter >= 5) {
                    settings.loggingVisible = true
                    Toast.makeText(requireContext().applicationContext, "Logging option enabled", Toast.LENGTH_LONG)
                        .show()
                }
            }

            binding.bFragmentAboutRate.setOnClickListener {
                try {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("market://details?id=$packageName")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (ignore: ActivityNotFoundException) {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }

        binding.bFragmentAboutDeveloperEmail.setOnClickListener {
            MaterialDialog(requireActivity()).show {
                lifecycleOwner(viewLifecycleOwner)
                title(R.string.about_fragment_write_email_dialog)
                icon(R.drawable.ic_about_feedback_24dp)
                positiveButton(android.R.string.cancel)
                negativeButton(android.R.string.yes) {
                    val emailIntent = Intent(Intent.ACTION_SENDTO)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .setData(Uri.Builder().scheme("mailto").build())
                        .putExtra(Intent.EXTRA_EMAIL, arrayOf("Dmitriy Krivoruchko <dkrivoruchko@gmail.com>"))
                        .putExtra(Intent.EXTRA_SUBJECT, "Screen Stream Feedback ($version)")
                    startActivity(
                        Intent.createChooser(emailIntent, getString(R.string.about_fragment_email_chooser_header))
                    )
                }
            }
        }

        binding.bFragmentAboutSources.setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/dkrivoruchko/ScreenStream")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )

            } catch (ignore: ActivityNotFoundException) {
            }
        }

        binding.bFragmentPrivacyPolicy.setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/dkrivoruchko/ScreenStream/blob/master/PrivacyPolicy.md")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )

            } catch (ignore: ActivityNotFoundException) {
            }
        }

        binding.bFragmentLicense.setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/dkrivoruchko/ScreenStream/blob/master/LICENSE")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )

            } catch (ignore: ActivityNotFoundException) {
            }
        }

    }
}