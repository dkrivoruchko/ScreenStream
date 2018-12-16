package info.dvkr.screenstream.ui.activity

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.core.view.forEach
import com.andrognito.flashbar.Flashbar
import com.google.android.material.bottomnavigation.BottomNavigationItemView
import com.google.android.material.bottomnavigation.BottomNavigationMenuView
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.model.FatalError
import info.dvkr.screenstream.data.model.FixableError
import info.dvkr.screenstream.data.other.getTag
import info.dvkr.screenstream.service.AppService
import info.dvkr.screenstream.service.ServiceMessage
import info.dvkr.screenstream.ui.fragments.AboutFragment
import info.dvkr.screenstream.ui.fragments.SettingsFragment
import info.dvkr.screenstream.ui.fragments.StreamFragment
import info.dvkr.screenstream.ui.router.FragmentRouter
import kotlinx.android.synthetic.main.activity_app.*
import timber.log.Timber

class AppActivity : BaseActivity() {
    companion object {
        fun getStartIntent(context: Context): Intent = Intent(context, AppActivity::class.java)
    }

    private val fragmentRouter: FragmentRouter by lazy {
        FragmentRouter(
            supportFragmentManager,
            StreamFragment.getFragmentCreator(),
            SettingsFragment.getFragmentCreator(),
            AboutFragment.getFragmentCreator()
        )
    }
    private var isStreamingBefore: Boolean = true
    private var errorPrevious: AppError? = null
    private var flashBar: Flashbar? = null

    override fun onBackPressed() {
        val itemId = fragmentRouter.onBackPressed()
        if (itemId != 0) bottom_navigation_activity_single.selectedItemId = itemId
        else super.onBackPressed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app)

        if (savedInstanceState == null) fragmentRouter.navigateTo(R.id.menu_stream_fragment)

        // Fix for https://github.com/material-components/material-components-android/issues/139
        (bottom_navigation_activity_single.getChildAt(0) as BottomNavigationMenuView).forEach { item ->
            (item as BottomNavigationItemView).findViewById<TextView>(R.id.largeLabel).setPadding(0, 0, 0, 0)
        }

        bottom_navigation_activity_single.setOnNavigationItemSelectedListener { menuItem ->
            return@setOnNavigationItemSelectedListener when (menuItem.itemId) {
                R.id.menu_stream_fragment,
                R.id.menu_settings_fragment,
                R.id.menu_about_fragment -> fragmentRouter.navigateTo(menuItem.itemId)

                R.id.menu_action_exit -> {
                    AppService.startForegroundService(this@AppActivity, AppService.IntentAction.Exit)
                    true
                }
                else -> false
            }
        }
    }

    override fun onServiceMessage(serviceMessage: ServiceMessage) {
        when (serviceMessage) {
            is ServiceMessage.ServiceState -> {
                Timber.tag(this@AppActivity.getTag("onServiceMessage")).d("Message: $serviceMessage")

                // StartStop button TODO ADD animation
                if (serviceMessage.isStreaming) {
                    fab_activity_single_start_stop.apply {
                        setImageResource(R.drawable.ic_fab_stop_24dp)
                        setOnClickListener {
                            AppService.startForegroundService(this@AppActivity, AppService.IntentAction.StopStream)
                        }
                    }
                    bottom_navigation_activity_single.menu.findItem(R.id.menu_fab).setTitle(R.string.bottom_menu_stop)
                } else {
                    fab_activity_single_start_stop.apply {
                        setImageResource(R.drawable.ic_fab_start_24dp)
                        setOnClickListener {
                            AppService.startForegroundService(this@AppActivity, AppService.IntentAction.StartStream)
                        }
                    }
                    bottom_navigation_activity_single.menu.findItem(R.id.menu_fab).setTitle(R.string.bottom_menu_start)
                }

                fab_activity_single_start_stop.isEnabled = serviceMessage.isBusy.not()

//                val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 0.5f, 1f)
//                val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.5f, 1f)
//                val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f)
//                ObjectAnimator.ofPropertyValuesHolder(fab_activity_single_start_stop, scaleX, scaleY,alpha).apply {
//                    interpolator = OvershootInterpolator()
//                    duration = 500
//                }.start()
//                fab_activity_single_start_stop.animate().scaleX(1f).scaleY(1f).alpha(1f)
//                    .setInterpolator(OvershootInterpolator())
//                    .setDuration(1000)
//                    .start()

                // MinimizeOnStream
                if (settingsReadOnly.minimizeOnStream && isStreamingBefore.not() && serviceMessage.isStreaming && serviceMessage.appError == null)
                    try {
                        startActivity(
                            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (ex: ActivityNotFoundException) {
                        Timber.tag(getTag("onServiceMessage")).e(ex)
                    }

                if (serviceMessage.appError != null) {
                    if (errorPrevious == null) showError(serviceMessage.appError)
                    else if (errorPrevious!!::class.java != serviceMessage.appError::class.java)
                        showError(serviceMessage.appError)
                }

                errorPrevious = serviceMessage.appError
                isStreamingBefore = serviceMessage.isStreaming
            }

            else -> super.onServiceMessage(serviceMessage)
        }
    }

    private fun showError(appError: AppError) {
        Timber.tag(getTag("showError")).d(appError.toString())

        val message: String = when (appError) {
            is FixableError.AddressInUseException -> getString(R.string.app_activity_error_port_in_use)
            is FixableError.CastSecurityException -> getString(R.string.app_activity_error_invalid_media_projection)
            is FixableError.AddressNotFoundException -> getString(R.string.app_activity_error_ip_address_not_found)
            is FatalError.BitmapFormatException -> getString(R.string.app_activity_error_wrong_image_format)
            else -> appError.toString()
        }

        flashBar = Flashbar.Builder(this)
            .gravity(Flashbar.Gravity.TOP)
            .title(R.string.app_activity_error_title)
            .titleColorRes(R.color.colorPrimary)
            .message(message)
            .messageColorRes(R.color.colorPrimary)
            .backgroundColorRes(R.color.colorErrorBackground)
            // .backgroundDrawable(R.drawable.bg_gradient) todo
            .positiveActionText(if (appError is FixableError) android.R.string.ok else R.string.app_activity_error_exit)
            .positiveActionTextColorRes(R.color.colorPrimary)
            .positiveActionTapListener(object : Flashbar.OnActionTapListener {
                override fun onActionTapped(bar: Flashbar) = bar.dismiss()
            })
            .barDismissListener(
                object : Flashbar.OnBarDismissListener {
                    override fun onDismissing(bar: Flashbar, isSwiped: Boolean) = Unit
                    override fun onDismissProgress(bar: Flashbar, progress: Float) = Unit
                    override fun onDismissed(bar: Flashbar, event: Flashbar.DismissEvent) {
                        if (appError is FixableError)
                            AppService.startForegroundService(this@AppActivity, AppService.IntentAction.RecoverError)
                        else
                            AppService.startForegroundService(this@AppActivity, AppService.IntentAction.Exit)
                    }
                }
            )
            .vibrateOn(Flashbar.Vibration.SHOW)
            .build()
            .apply { show() }
    }
}