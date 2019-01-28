package info.dvkr.screenstream.ui.fragment

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.ui.router.FragmentRouter
import kotlinx.android.synthetic.main.fragment_about.*

class AboutFragment : Fragment() {

    companion object {
        fun getFragmentCreator() = object : FragmentRouter.FragmentCreator {
            override fun getMenuItemId(): Int = R.id.menu_about_fragment
            override fun getTag(): String = AboutFragment::class.java.name
            override fun newInstance(): Fragment = AboutFragment()
        }
    }

    private var version: String = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_about, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        with(requireActivity()) {
            try {
                version = packageManager.getPackageInfo(packageName, 0).versionName
                tv_fragment_about_version.text = getString(R.string.about_fragment_app_version, version)
            } catch (t: Throwable) {
                XLog.e(getLog("onViewCreated", "getPackageInfo"), t)
            }

            b_fragment_about_rate.setOnClickListener {
                try {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("market://details?id=$packageName")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (ex: ActivityNotFoundException) {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }

        b_fragment_about_developer_email.setOnClickListener {
            val emailIntent = Intent(Intent.ACTION_SENDTO)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setData(Uri.Builder().scheme("mailto").build())
                .putExtra(Intent.EXTRA_EMAIL, arrayOf("Dmitriy Krivoruchko <dkrivoruchko@gmail.com>"))
                .putExtra(Intent.EXTRA_SUBJECT, "Screen Stream Feedback ($version)")
            startActivity(Intent.createChooser(emailIntent, getString(R.string.about_fragment_email_chooser_header)))
        }

        b_fragment_about_sources.setOnClickListener {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/dkrivoruchko/ScreenStream")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}