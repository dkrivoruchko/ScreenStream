package info.dvkr.screenstream.ui


import android.app.Activity
import android.content.*
import android.graphics.Point
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.support.v4.content.ContextCompat
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.crashlytics.android.Crashlytics
import com.jakewharton.rxrelay.PublishRelay
import com.mikepenz.materialdrawer.Drawer
import com.mikepenz.materialdrawer.DrawerBuilder
import com.mikepenz.materialdrawer.model.DividerDrawerItem
import com.mikepenz.materialdrawer.model.PrimaryDrawerItem
import com.tapadoo.alerter.Alerter
import info.dvkr.screenstream.BuildConfig
import info.dvkr.screenstream.R
import info.dvkr.screenstream.dagger.component.NonConfigurationComponent
import info.dvkr.screenstream.model.Settings
import info.dvkr.screenstream.presenter.StartActivityPresenter
import info.dvkr.screenstream.service.ForegroundService
import info.dvkr.screenstream.service.ForegroundServiceView
import kotlinx.android.synthetic.main.activity_start.*
import kotlinx.android.synthetic.main.server_address.view.*
import rx.Observable
import rx.android.schedulers.AndroidSchedulers
import java.net.BindException
import javax.inject.Inject

class StartActivity : BaseActivity(), StartActivityView {

    private val TAG = "StartActivity"

    companion object {
        private const val REQUEST_CODE_SCREEN_CAPTURE = 10

        private const val EXTRA_DATA = "EXTRA_DATA"
        const val ACTION_START_STREAM = "ACTION_START_STREAM"
        const val ACTION_STOP_STREAM = "ACTION_STOP_STREAM"
        const val ACTION_EXIT = "ACTION_EXIT"
        const val ACTION_ERROR = "ACTION_ERROR"

        fun getStartIntent(context: Context): Intent {
            return Intent(context, StartActivity::class.java)
        }

        fun getStartIntent(context: Context, action: String): Intent {
            return Intent(context, StartActivity::class.java)
                    .putExtra(EXTRA_DATA, action)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }

    @Inject internal lateinit var presenter: StartActivityPresenter
    @Inject internal lateinit var settings: Settings
    private val fromEvents = PublishRelay.create<StartActivityView.FromEvent>()
    private lateinit var drawer: Drawer
    private var canStart: Boolean = true
    private val clipboard: ClipboardManager by lazy { getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    override fun fromEvent(): Observable<StartActivityView.FromEvent> = fromEvents.asObservable()

    override fun toEvent(toEvent: StartActivityView.ToEvent) {
        Observable.just(toEvent).subscribeOn(AndroidSchedulers.mainThread()).subscribe { event ->
            if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] toEvent: ${event.javaClass.simpleName}")

            when (event) {
                is StartActivityView.ToEvent.TryToStart -> {
                    val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    try {
                        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_CAPTURE)
                    } catch (ex: ActivityNotFoundException) {
                        fromEvents.call(StartActivityView.FromEvent.Error(ex))
                    }
                }

                is StartActivityView.ToEvent.OnStreamStartStop -> {
                    setStreamRunning(event.running)
                    if (event.running && settings.minimizeOnStream)
                        try {
                            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (ex: ActivityNotFoundException) {
                            Crashlytics.log(1, TAG, "OnStreamStartStop: minimizeOnStream: ActivityNotFoundException")
                            Crashlytics.logException(ex)
                        }
                }

                is StartActivityView.ToEvent.ResizeFactor -> showResizeFactor(event.value)
                is StartActivityView.ToEvent.EnablePin -> showEnablePin(event.value)
                is StartActivityView.ToEvent.SetPin -> textViewPinValue.text = event.value
                is StartActivityView.ToEvent.StreamRunning -> setStreamRunning(event.running)

                is StartActivityView.ToEvent.Error -> {
                    canStart = true
                    event.error?.let {
                        val alerter = Alerter.create(this)

                        when (it) {
                            is ActivityNotFoundException -> {
                                canStart = false
                                alerter.setTitle(R.string.start_activity_alert_title_error_activity_not_found)
                                        .setText(R.string.start_activity_error_activity_not_found)
                            }

                            is UnsupportedOperationException -> {
                                canStart = false
                                alerter.setTitle(R.string.start_activity_alert_title_error)
                                        .setText(R.string.start_activity_error_wrong_image_format)
                            }

                            is BindException -> {
                                canStart = false
                                alerter.setTitle(R.string.start_activity_alert_title_error_network)
                                val msg = it.message?.drop(13) ?: getString(R.string.start_activity_error_network_unknown)
                                if (msg.contains("EADDRINUSE")) alerter.setText(R.string.start_activity_error_port_in_use)
                                else alerter.setText(msg)
                            }

                            is SecurityException -> {
                                alerter.setTitle(R.string.start_activity_alert_title_error)
                                        .setText(R.string.start_activity_error_invalid_media_projection)
                            }

                            else -> {
                                canStart = false
                                alerter.setTitle(R.string.start_activity_alert_title_error_unknown)
                                        .setText(it.message)
                            }
                        }

                        alerter.setBackgroundColorRes(R.color.colorAccent)
                                .enableInfiniteDuration(true)
                                .enableSwipeToDismiss()
                                .show()
                    }
                }

                is StartActivityView.ToEvent.CurrentClients -> {
                    val clientsCount = event.clientsList.filter { !it.disconnected }.count()
                    textViewConnectedClients.text = getString(R.string.start_activity_connected_clients).format(clientsCount)
                }

                is StartActivityView.ToEvent.CurrentInterfaces -> {
                    showServerAddresses(event.interfaceList, settings.severPort)
                }

                is StartActivityView.ToEvent.TrafficPoint -> {
                    val mbit = (event.trafficPoint.bytes * 8).toDouble() / 1024 / 1024
                    textViewCurrentTraffic.text = getString(R.string.start_activity_current_traffic).format(mbit)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onCreate: Start")
        Crashlytics.log(1, TAG, "onCreate: Start")

        setContentView(R.layout.activity_start)
        setSupportActionBar(toolbarStart)
        supportActionBar?.setTitle(R.string.start_activity_name)

        startService(ForegroundService.getIntent(applicationContext, ForegroundService.ACTION_INIT))
        toggleButtonStartStop.isEnabled = false
        textViewConnectedClients.text = getString(R.string.start_activity_connected_clients).format(0)
        textViewCurrentTraffic.text = getString(R.string.start_activity_current_traffic).format(0f)
        presenter.attach(this)

        toggleButtonStartStop.setOnClickListener { _ ->
            if (toggleButtonStartStop.isChecked) {
                toggleButtonStartStop.isChecked = false
                if (!canStart) return@setOnClickListener
                toggleButtonStartStop.isEnabled = false
                fromEvents.call(StartActivityView.FromEvent.TryStartStream())
            } else {
                toggleButtonStartStop.isChecked = true
                toggleButtonStartStop.isEnabled = false
                fromEvents.call(StartActivityView.FromEvent.StopStream())
            }
        }

        drawer = DrawerBuilder()
                .withActivity(this)
                .withToolbar(toolbarStart)
                .withHeader(R.layout.activity_start_drawer_header)
                .withHasStableIds(true)
                .addDrawerItems(
                        PrimaryDrawerItem().withIdentifier(1).withName(R.string.start_activity_drawer_main).withSelectable(false).withIcon(R.drawable.ic_drawer_main_24dp),
                        PrimaryDrawerItem().withIdentifier(2).withName(R.string.start_activity_drawer_traffic_clients).withSelectable(false).withIcon(R.drawable.ic_drawer_connected_24dp),
                        PrimaryDrawerItem().withIdentifier(3).withName(R.string.start_activity_drawer_settings).withSelectable(false).withIcon(R.drawable.ic_drawer_settings_24dp),
                        //                        DividerDrawerItem(),
//                        PrimaryDrawerItem().withIdentifier(4).withName("Instructions").withSelectable(false).withIcon(R.drawable.ic_drawer_instructions_24dp),
//                        PrimaryDrawerItem().withIdentifier(5).withName("Local test").withSelectable(false).withIcon(R.drawable.ic_drawer_test_24dp),
                        DividerDrawerItem(),
                        PrimaryDrawerItem().withIdentifier(6).withName(R.string.start_activity_drawer_rate_app).withSelectable(false).withIcon(R.drawable.ic_drawer_rateapp_24dp),
                        PrimaryDrawerItem().withIdentifier(7).withName(R.string.start_activity_drawer_about).withSelectable(false).withIcon(R.drawable.ic_drawer_about_24dp)
                )
                .addStickyDrawerItems(
                        PrimaryDrawerItem().withIdentifier(8).withName(R.string.start_activity_drawer_exit).withIcon(R.drawable.ic_drawer_exit_24pd)
                )
                .withOnDrawerItemClickListener { _, _, drawerItem ->
                    if (drawerItem.identifier == 1L) if (drawer.isDrawerOpen) drawer.closeDrawer()
                    if (drawerItem.identifier == 2L) startActivity(ClientsActivity.getStartIntent(this))
                    if (drawerItem.identifier == 3L) startActivity(SettingsActivity.getStartIntent(this))

                    if (drawerItem.identifier == 6L) {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
                        } catch (ex: ActivityNotFoundException) {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
                        }
                    }

                    if (drawerItem.identifier == 7L) startActivity(AboutActivity.getStartIntent(this))
                    if (drawerItem.identifier == 8L) fromEvents.call(StartActivityView.FromEvent.AppExit())
                    true
                }
                .build()

        drawer.deselect()

        showResizeFactor(settings.resizeFactor)
        showEnablePin(settings.enablePin)
        textViewPinValue.text = settings.currentPin

        onNewIntent(intent)

        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onCreate: End")
        Crashlytics.log(1, TAG, "onCreate: End")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val action = intent.getStringExtra(EXTRA_DATA)
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onNewIntent: action = $action")
        if (null == action) return

        intent.removeExtra(EXTRA_DATA)
        this.intent = intent

        Crashlytics.log(1, TAG, "Action: $action")
        when (action) {
            ACTION_START_STREAM -> {
                if (!canStart) return
                toggleButtonStartStop.isEnabled = false
                fromEvents.call(StartActivityView.FromEvent.TryStartStream())
            }

            ACTION_STOP_STREAM -> {
                toggleButtonStartStop.isEnabled = false
                fromEvents.call(StartActivityView.FromEvent.StopStream())
            }

            ACTION_EXIT -> fromEvents.call(StartActivityView.FromEvent.AppExit())
        }
    }

    override fun onResume() {
        super.onResume()
        fromEvents.call(StartActivityView.FromEvent.CurrentInterfacesRequest())
        fromEvents.call(StartActivityView.FromEvent.GetError())
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
        presenter.detach()
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onDestroy: End")
        Crashlytics.log(1, TAG, "onDestroy: End")
        super.onDestroy()
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onActivityResult: $requestCode")
        when (requestCode) {
            REQUEST_CODE_SCREEN_CAPTURE -> {
                if (Activity.RESULT_OK != resultCode) {
                    toggleButtonStartStop.isEnabled = true
                    Alerter.create(this).setTitle(R.string.start_activity_alert_title_warring)
                            .setText(R.string.start_activity_cast_permission_required)
                            .setBackgroundColorRes(R.color.colorWarring)
                            .setDuration(5000)
                            .enableProgress(true)
                            .enableSwipeToDismiss()
                            .show()
                    if (BuildConfig.DEBUG_MODE) Log.w(TAG, "onActivityResult: Screen Cast permission denied")
                    Crashlytics.log(1, TAG, "onActivityResult: Screen Cast permission denied")
                    return
                }

                if (null == data) {
                    toggleButtonStartStop.isEnabled = true
                    if (BuildConfig.DEBUG_MODE) Log.e(TAG, "onActivityResult ERROR: data = null")
                    val error = IllegalStateException("onActivityResult: data = null")
                    Crashlytics.logException(error)
                    fromEvents.call(StartActivityView.FromEvent.Error(error))
                    return
                }
                startService(ForegroundService.getStartStreamIntent(applicationContext, data))
            }
        }
    }

    // Private methods
    private fun setStreamRunning(running: Boolean) {
        toggleButtonStartStop.isChecked = running
        toggleButtonStartStop.isEnabled = true
        if (settings.enablePin) {
            if (running && settings.hidePinOnStart) textViewPinValue.setText(R.string.start_activity_pin_asterisks)
            else textViewPinValue.text = settings.currentPin
        }
    }

    private fun showServerAddresses(interfaceList: List<ForegroundServiceView.Interface>, serverPort: Int) {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] showServerAddresses")

        linearLayoutServerAddressList.removeAllViews()
        val layoutInflater = LayoutInflater.from(this)
        if (interfaceList.isEmpty()) {
            val addressView = layoutInflater.inflate(R.layout.server_address, linearLayoutServerAddressList, false)
            with(addressView) {
                textViewInterfaceName.text = ""
                textViewInterfaceAddress.text = getString(R.string.start_activity_no_address)
                textViewInterfaceAddress.setTextColor(ContextCompat.getColor(context, R.color.colorAccent))
            }
            linearLayoutServerAddressList.addView(addressView)
        } else {
            for ((name, address) in interfaceList) {
                val addressView = layoutInflater.inflate(R.layout.server_address, linearLayoutServerAddressList, false)
                with(addressView) {
                    textViewInterfaceName.text = "$name:"
                    textViewInterfaceAddress.text = "http://${address.hostAddress}:$serverPort"
                    imageViewCopy.setOnClickListener {
                        clipboard.primaryClip = ClipData.newPlainText(textViewInterfaceAddress.text, textViewInterfaceAddress.text)
                        Toast.makeText(applicationContext, R.string.start_activity_copied, Toast.LENGTH_LONG).show()
                    }
                }
                linearLayoutServerAddressList.addView(addressView)
            }
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
            textViewPinText.text = getString(R.string.start_activity_pin)
            textViewPinValue.text = settings.currentPin
            textViewPinValue.visibility = View.VISIBLE
        } else {
            textViewPinText.text = getString(R.string.start_activity_pin_disabled)
            textViewPinValue.visibility = View.GONE
        }
    }
}