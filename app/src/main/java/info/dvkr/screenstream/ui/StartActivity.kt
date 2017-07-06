package info.dvkr.screenstream.ui


import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.support.v7.app.AlertDialog
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.crashlytics.android.Crashlytics
import com.mikepenz.materialdrawer.Drawer
import com.mikepenz.materialdrawer.DrawerBuilder
import com.mikepenz.materialdrawer.model.DividerDrawerItem
import com.mikepenz.materialdrawer.model.PrimaryDrawerItem
import info.dvkr.screenstream.BuildConfig
import info.dvkr.screenstream.R
import info.dvkr.screenstream.dagger.component.NonConfigurationComponent
import info.dvkr.screenstream.model.AppEvent
import info.dvkr.screenstream.model.Settings
import info.dvkr.screenstream.presenter.StartActivityPresenter
import info.dvkr.screenstream.service.ForegroundService
import kotlinx.android.synthetic.main.activity_start.*
import rx.Observable
import rx.subjects.PublishSubject
import rx.subscriptions.CompositeSubscription
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import javax.inject.Inject

class StartActivity : BaseActivity(), StartActivityView {
    private val TAG = "StartActivity"

    companion object {
        private const val REQUEST_CODE_SCREEN_CAPTURE = 10

        private const val EXTRA_DATA = "EXTRA_DATA"
        const val ACTION_START_STREAM = "ACTION_START_STREAM"
        const val ACTION_STOP_STREAM = "ACTION_STOP_STREAM"
        const val ACTION_EXIT = "ACTION_EXIT"
        const val ACTION_APP_STATUS = "ACTION_APP_STATUS" // Just for starting Activity
        private const val ACTION_UNKNOWN_ERROR = "ACTION_UNKNOWN_ERROR"

        fun getStartIntent(context: Context): Intent {
            return Intent(context, StartActivity::class.java)
        }

        fun getStartIntent(context: Context, action: String): Intent {
            return Intent(context, StartActivity::class.java)
                    .putExtra(EXTRA_DATA, action)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        fun getErrorIntent(context: Context, error: String): Intent {
            return Intent(context, StartActivity::class.java)
                    .putExtra(EXTRA_DATA, ACTION_UNKNOWN_ERROR)
                    .putExtra(ACTION_UNKNOWN_ERROR, error)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }

    private val mSubscriptions = CompositeSubscription()
    private val mStartEvents = PublishSubject.create<StartActivityView.Event>()

    @Inject internal lateinit var mPresenter: StartActivityPresenter
    @Inject internal lateinit var mSettings: Settings

    private lateinit var mDrawer: Drawer
    private var mDialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onCreate: Start")

        setContentView(R.layout.activity_start)
        setSupportActionBar(toolbarStart)
        supportActionBar?.setTitle(R.string.start_activity_name)

        startService(ForegroundService.getIntent(applicationContext, ForegroundService.ACTION_INIT))

        mPresenter.attach(this)

        mSubscriptions.add(mSettings.resizeFactorObservable.subscribe({ this.showResizeFactor(it) }))
        mSubscriptions.add(mSettings.enablePinObservable.subscribe({ this.showEnablePin(it) }))
        mSubscriptions.add(mSettings.currentPinObservable.subscribe({ textViewPinValue.text = it }))
        mSubscriptions.add(mSettings.severPortObservable.subscribe({ this.showServerAddresses(it) }))

        toggleButtonStartStop.setOnClickListener { _ ->
            if (toggleButtonStartStop.isChecked) {
                toggleButtonStartStop.isChecked = false
                mStartEvents.onNext(StartActivityView.Event.TryStartStream())
            } else {
                toggleButtonStartStop.isChecked = true
                mStartEvents.onNext(StartActivityView.Event.StopStream())
            }
        }

        mDrawer = DrawerBuilder()
                .withActivity(this)
                .withToolbar(toolbarStart)
                .withHeader(R.layout.activity_start_drawer_header)
                .withHasStableIds(true)
                .addDrawerItems(
                        PrimaryDrawerItem().withIdentifier(1).withName("Main").withSelectable(false).withIcon(R.drawable.ic_drawer_main_24dp),
                        PrimaryDrawerItem().withIdentifier(2).withName("Connected clients").withSelectable(false).withIcon(R.drawable.ic_drawer_connected_24dp),
                        PrimaryDrawerItem().withIdentifier(3).withName("Settings").withSelectable(false).withIcon(R.drawable.ic_drawer_settings_24dp),
                        DividerDrawerItem(),
                        PrimaryDrawerItem().withIdentifier(4).withName("Instructions").withSelectable(false).withIcon(R.drawable.ic_drawer_instructions_24dp),
                        PrimaryDrawerItem().withIdentifier(5).withName("Local test").withSelectable(false).withIcon(R.drawable.ic_drawer_test_24dp),
                        DividerDrawerItem(),
                        PrimaryDrawerItem().withIdentifier(6).withName("Rate app").withSelectable(false).withIcon(R.drawable.ic_drawer_rateapp_24dp),
                        PrimaryDrawerItem().withIdentifier(7).withName("Feedback").withSelectable(false).withIcon(R.drawable.ic_drawer_feedback_24dp),
                        PrimaryDrawerItem().withIdentifier(8).withName("Sources").withSelectable(false).withIcon(R.drawable.ic_drawer_sources_24dp)
                )
                .addStickyDrawerItems(
                        PrimaryDrawerItem().withIdentifier(9).withName("Exit").withIcon(R.drawable.ic_drawer_exit_24pd)
                )
                .withOnDrawerItemClickListener { _, _, drawerItem ->
                    if (drawerItem.identifier == 3L) startActivity(SettingsActivity.getStartIntent(this))

                    if (drawerItem.identifier == 6L) {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
                        } catch (ex: ActivityNotFoundException) {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
                        }
                    }

                    if (drawerItem.identifier == 7L) {
                        val emailIntent = Intent(Intent.ACTION_SENDTO)
                                .setData(Uri.Builder().scheme("mailto").build())
                                .putExtra(Intent.EXTRA_EMAIL, arrayOf(StartActivityView.FEEDBACK_EMAIL_ADDRESS))
                                .putExtra(Intent.EXTRA_SUBJECT, StartActivityView.FEEDBACK_EMAIL_SUBJECT)
                        startActivity(Intent.createChooser(emailIntent, StartActivityView.FEEDBACK_EMAIL_NAME))
                    }

                    if (drawerItem.identifier == 8L) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/dkrivoruchko/ScreenStream")))
                    }

                    if (drawerItem.identifier == 9L) mStartEvents.onNext(StartActivityView.Event.AppExit())
                    true
                }
                .build()

        onNewIntent(intent)
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onCreate: End")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val action = intent.getStringExtra(EXTRA_DATA)
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onNewIntent: action = $action")
        if (null == action) return

        when (action) {
            ACTION_START_STREAM -> {
                toggleButtonStartStop.isChecked = false
                mStartEvents.onNext(StartActivityView.Event.TryStartStream())
            }

            ACTION_STOP_STREAM -> {
                toggleButtonStartStop.isChecked = true
                mStartEvents.onNext(StartActivityView.Event.StopStream())
            }

            ACTION_EXIT -> mStartEvents.onNext(StartActivityView.Event.AppExit())

            ACTION_UNKNOWN_ERROR -> {
                val errorDescription = intent.getStringExtra(ACTION_UNKNOWN_ERROR)
                showErrorDialog(getString(R.string.start_activity_error_unknown) + "\n$errorDescription")
            }
        }
    }

    override fun onBackPressed() {
        if (mDrawer.isDrawerOpen) mDrawer.closeDrawer() else mDrawer.openDrawer()
    }

    override fun onStop() {
        if (mDrawer.isDrawerOpen) mDrawer.closeDrawer()
        super.onStop()
    }

    override fun inject(injector: NonConfigurationComponent) = injector.inject(this)

    override fun onDestroy() {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onDestroy: Start")
        mSubscriptions.clear()
        mDialog?.let { if (it.isShowing) it.dismiss() }
        mPresenter.detach()
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onDestroy: End")
        super.onDestroy()
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onActivityResult: $requestCode")
        when (requestCode) {
            REQUEST_CODE_SCREEN_CAPTURE -> {
                if (Activity.RESULT_OK != resultCode) {
                    showErrorDialog(getString(R.string.start_activity_error_cast_permission_deny))
                    if (BuildConfig.DEBUG_MODE) Log.w(TAG, "onActivityResult: Screen Cast permission denied")
                    return
                }

                if (null == data) {
                    if (BuildConfig.DEBUG_MODE) Log.e(TAG, "onActivityResult ERROR: data = null")
                    Crashlytics.logException(IllegalStateException("onActivityResult ERROR: data = null"))
                    showErrorDialog(getString(R.string.start_activity_error_unknown) + "onActivityResult: data = null")
                    return
                }
                startService(ForegroundService.getStartStreamIntent(this, data))
            }
        }
    }

    override fun onEvent(): Observable<StartActivityView.Event> = mStartEvents.asObservable()

    override fun onAppStatus(appStatus: Set<String>) {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onAppStatus")

        toggleButtonStartStop.isEnabled = appStatus.isEmpty()

        if (appStatus.contains(AppEvent.APP_STATUS_ERROR_SERVER_PORT_BUSY))
            showErrorDialog(getString(R.string.start_activity_error_port_in_use))

        if (appStatus.contains(AppEvent.APP_STATUS_ERROR_WRONG_IMAGE_FORMAT))
            showErrorDialog(getString(R.string.start_activity_error_wrong_image_format))
    }

    override fun onTryToStart() {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onTryToStart")
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_CAPTURE)
    }

    override fun onStreamStart() {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onStreamStart")
        toggleButtonStartStop.isChecked = true

        if (mSettings.enablePin && mSettings.hidePinOnStart)
            textViewPinValue.setText(R.string.start_activity_pin_asterisks)

        if (mSettings.minimizeOnStream)
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onStreamStop() {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onStreamStop")
        toggleButtonStartStop.isChecked = false

        if (mSettings.enablePin && mSettings.hidePinOnStart)
            textViewPinValue.text = mSettings.currentPin
    }

    override fun onConnectedClients(clientAddresses: List<InetSocketAddress>) {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onConnectedClients")
        textViewConnectedClients.text = getString(R.string.start_activity_connected_clients).format(clientAddresses.size)
    }

    private fun showResizeFactor(resizeFactor: Int) {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] showResizeFactor")

        val defaultDisplay = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        val screenSize = Point()
        defaultDisplay.getSize(screenSize)
        val scale = resizeFactor / 100f
        textViewResizeFactor.text = getString(R.string.start_activity_resize_factor) +
                " $resizeFactor%: ${(screenSize.x * scale).toInt()}x${(screenSize.y * scale).toInt()}"
    }

    private fun showEnablePin(enablePin: Boolean) {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] showEnablePin")
        if (enablePin) {
            textViewPinValue.text = mSettings.currentPin
            textViewPinDisabled.visibility = View.GONE
            textViewPinText.visibility = View.VISIBLE
            textViewPinValue.visibility = View.VISIBLE
        } else {
            textViewPinDisabled.visibility = View.VISIBLE
            textViewPinText.visibility = View.GONE
            textViewPinValue.visibility = View.GONE
        }
    }

    private fun showServerAddresses(serverPort: Int) {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] showServerAddresses")

        linearLayoutServerAddressList.removeAllViews()
        val layoutInflater = LayoutInflater.from(this)
        try {
            val enumeration = NetworkInterface.getNetworkInterfaces()
            while (enumeration.hasMoreElements()) {
                val networkInterface = enumeration.nextElement()
                val enumIpAddr = networkInterface.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        val addressView = layoutInflater.inflate(R.layout.server_address, null)
                        val interfaceView = addressView.findViewById(R.id.textViewInterfaceName) as TextView
                        interfaceView.text = "${networkInterface.displayName}:"
                        val interfaceAddress = addressView.findViewById(R.id.textViewInterfaceAddress) as TextView
                        interfaceAddress.text = "http://${inetAddress.hostAddress}:$serverPort"
                        linearLayoutServerAddressList.addView(addressView)
                    }
                }
            }
        } catch (ex: Throwable) {
            if (BuildConfig.DEBUG_MODE) Log.e(TAG, ex.toString())
            Crashlytics.logException(ex)
        }
    }

    private fun showErrorDialog(errorMessage: String) {
        mDialog = AlertDialog.Builder(this)
                .setIcon(R.drawable.ic_message_error_24dp)
                .setTitle(R.string.start_activity_error_title)
                .setMessage(errorMessage)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, null)
                .create()
        mDialog?.show()
    }
}