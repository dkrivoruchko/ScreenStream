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
import info.dvkr.screenstream.model.Settings
import info.dvkr.screenstream.presenter.StartActivityPresenter
import info.dvkr.screenstream.service.ForegroundService
import info.dvkr.screenstream.service.ForegroundServiceView
import kotlinx.android.synthetic.main.activity_start.*
import rx.Observable
import rx.android.schedulers.AndroidSchedulers
import rx.subjects.PublishSubject
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

    @Inject internal lateinit var presenter: StartActivityPresenter
    @Inject internal lateinit var settings: Settings

    private val fromEvents = PublishSubject.create<StartActivityView.FromEvent>()

    private lateinit var drawer: Drawer
    private var dialog: Dialog? = null

    override fun fromEvent(): Observable<StartActivityView.FromEvent> = fromEvents.asObservable()

    override fun toEvent(toEvent: StartActivityView.ToEvent) {
        Observable.just(toEvent).observeOn(AndroidSchedulers.mainThread()).subscribe { event ->
            if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] toEvent: ${event.javaClass.simpleName}")

            when (event) {
                is StartActivityView.ToEvent.TryToStart -> {
                    val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_CAPTURE)
                }

                is StartActivityView.ToEvent.StreamStart -> {
                    toggleButtonStartStop.isChecked = true

                    if (settings.enablePin && settings.hidePinOnStart)
                        textViewPinValue.setText(R.string.start_activity_pin_asterisks)

                    if (settings.minimizeOnStream)
                        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }

                is StartActivityView.ToEvent.StreamStop -> {
                    toggleButtonStartStop.isChecked = false

                    if (settings.enablePin && settings.hidePinOnStart)
                        textViewPinValue.text = settings.currentPin
                }

                is StartActivityView.ToEvent.ResizeFactor -> showResizeFactor(event.value)
                is StartActivityView.ToEvent.EnablePin -> showEnablePin(event.value)
                is StartActivityView.ToEvent.SetPin -> textViewPinValue.text = event.value

                is StartActivityView.ToEvent.CurrentClients -> {
                    textViewConnectedClients.text = getString(R.string.start_activity_connected_clients).format(event.clientsList.size)
                }

                is StartActivityView.ToEvent.CurrentInterfaces -> {
                    showServerAddresses(event.interfaceList, settings.severPort)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onCreate: Start")

        setContentView(R.layout.activity_start)
        setSupportActionBar(toolbarStart)
        supportActionBar?.setTitle(R.string.start_activity_name)

        startService(ForegroundService.getIntent(applicationContext, ForegroundService.ACTION_INIT))

        presenter.attach(this)

        toggleButtonStartStop.setOnClickListener { _ ->
            if (toggleButtonStartStop.isChecked) {
                toggleButtonStartStop.isChecked = false
                fromEvents.onNext(StartActivityView.FromEvent.TryStartStream())
            } else {
                toggleButtonStartStop.isChecked = true
                fromEvents.onNext(StartActivityView.FromEvent.StopStream())
            }
        }

        drawer = DrawerBuilder()
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

                    if (drawerItem.identifier == 9L) fromEvents.onNext(StartActivityView.FromEvent.AppExit())
                    true
                }
                .build()

        showResizeFactor(settings.resizeFactor)
        showEnablePin(settings.enablePin)
        textViewPinValue.text = settings.currentPin

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
                fromEvents.onNext(StartActivityView.FromEvent.TryStartStream())
            }

            ACTION_STOP_STREAM -> {
                toggleButtonStartStop.isChecked = true
                fromEvents.onNext(StartActivityView.FromEvent.StopStream())
            }

            ACTION_EXIT -> fromEvents.onNext(StartActivityView.FromEvent.AppExit())

            ACTION_UNKNOWN_ERROR -> {
                val errorDescription = intent.getStringExtra(ACTION_UNKNOWN_ERROR)
                showErrorDialog(getString(R.string.start_activity_error_unknown) + "\n$errorDescription")
            }
        }
    }

    override fun onBackPressed() {
        if (drawer.isDrawerOpen) drawer.closeDrawer() else drawer.openDrawer()
    }

    override fun onStop() {
        if (drawer.isDrawerOpen) drawer.closeDrawer()
        super.onStop()
    }

    override fun inject(injector: NonConfigurationComponent) = injector.inject(this)

    override fun onDestroy() {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onDestroy: Start")
        dialog?.let { if (it.isShowing) it.dismiss() }
        presenter.detach()
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


    //    override fun onAppStatus(appStatus: Set<String>) {
//        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onAppStatus")
//
//        toggleButtonStartStop.isEnabled = appStatus.isEmpty()
//
//        if (appStatus.contains(AppStatus.APP_STATUS_ERROR_SERVER_PORT_BUSY))
//            showErrorDialog(getString(R.string.start_activity_error_port_in_use))
//
//        if (appStatus.contains(AppStatus.APP_STATUS_ERROR_WRONG_IMAGE_FORMAT))
//            showErrorDialog(getString(R.string.start_activity_error_wrong_image_format))
//    }
//

    // Private methods

    private fun showServerAddresses(interfaceList: List<ForegroundServiceView.Interface>, serverPort: Int) {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] showServerAddresses")

        linearLayoutServerAddressList.removeAllViews()
        val layoutInflater = LayoutInflater.from(this)
        for (item in interfaceList) {
            val addressView = layoutInflater.inflate(R.layout.server_address, null)
            val interfaceView = addressView.findViewById(R.id.textViewInterfaceName) as TextView
            interfaceView.text = "${item.name}:"
            val interfaceAddress = addressView.findViewById(R.id.textViewInterfaceAddress) as TextView
            interfaceAddress.text = "http://${item.address}:$serverPort"
            linearLayoutServerAddressList.addView(addressView)
        }
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
            textViewPinValue.text = settings.currentPin
            textViewPinDisabled.visibility = View.GONE
            textViewPinText.visibility = View.VISIBLE
            textViewPinValue.visibility = View.VISIBLE
        } else {
            textViewPinDisabled.visibility = View.VISIBLE
            textViewPinText.visibility = View.GONE
            textViewPinValue.visibility = View.GONE
        }
    }

    private fun showErrorDialog(errorMessage: String) {
        dialog = AlertDialog.Builder(this)
                .setIcon(R.drawable.ic_message_error_24dp)
                .setTitle(R.string.start_activity_error_title)
                .setMessage(errorMessage)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, null)
                .create()
        dialog?.show()
    }
}