package info.dvkr.screenstream.ui


import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.support.v4.content.ContextCompat
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.crashlytics.android.Crashlytics
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
import rx.subjects.PublishSubject
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
    private val fromEvents = PublishSubject.create<StartActivityView.FromEvent>()
    private lateinit var drawer: Drawer
    private var canStart: Boolean = true

    override fun fromEvent(): Observable<StartActivityView.FromEvent> = fromEvents.asObservable()

    override fun toEvent(toEvent: StartActivityView.ToEvent) {
        Observable.just(toEvent).observeOn(AndroidSchedulers.mainThread()).subscribe { event ->
            if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] toEvent: ${event.javaClass.simpleName}")

            when (event) {
                is StartActivityView.ToEvent.TryToStart -> {
                    val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_CAPTURE)
                }

                is StartActivityView.ToEvent.StreamStartStop -> {
                    setStreamRunning(event.running)
                    if (event.running && settings.minimizeOnStream)
                        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
                            is SecurityException -> {
                                alerter.setTitle(R.string.start_activity_alert_title_warring)
                                        .setText(R.string.start_activity_cast_permission_required)
                                        .setBackgroundColorRes(R.color.colorWarring)
                            }
                            is UnsupportedOperationException -> {
                                canStart = false
                                alerter.setTitle(R.string.start_activity_alert_title_error)
                                        .setText(R.string.start_activity_error_wrong_image_format)
                                        .setBackgroundColorRes(R.color.colorAccent)
                            }
                            is BindException -> {
                                canStart = false
                                alerter.setTitle(R.string.start_activity_alert_title_error)
                                        .setText(R.string.start_activity_error_port_in_use)
                                        .setBackgroundColorRes(R.color.colorAccent)
                            }
                            else -> {
                                canStart = false
                                alerter.setTitle(R.string.start_activity_alert_title_error_unknown)
                                        .setText(it.message)
                                        .setBackgroundColorRes(R.color.colorAccent)
                            }
                        }
                        alerter.enableInfiniteDuration(true)
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

        setContentView(R.layout.activity_start)
        setSupportActionBar(toolbarStart)
        supportActionBar?.setTitle(R.string.start_activity_name)

        startService(ForegroundService.getIntent(applicationContext, ForegroundService.ACTION_INIT))

        presenter.attach(this)

        toggleButtonStartStop.setOnClickListener { _ ->
            if (toggleButtonStartStop.isChecked) {
                toggleButtonStartStop.isChecked = false
                if (!canStart) return@setOnClickListener
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
                        PrimaryDrawerItem().withIdentifier(1).withName(R.string.start_activity_drawer_main).withSelectable(false).withIcon(R.drawable.ic_drawer_main_24dp),
                        PrimaryDrawerItem().withIdentifier(2).withName(R.string.start_activity_drawer_traffic_clients).withSelectable(false).withIcon(R.drawable.ic_drawer_connected_24dp),
                        PrimaryDrawerItem().withIdentifier(3).withName(R.string.start_activity_drawer_settings).withSelectable(false).withIcon(R.drawable.ic_drawer_settings_24dp),
                        //                        DividerDrawerItem(),
//                        PrimaryDrawerItem().withIdentifier(4).withName("Instructions").withSelectable(false).withIcon(R.drawable.ic_drawer_instructions_24dp),
//                        PrimaryDrawerItem().withIdentifier(5).withName("Local test").withSelectable(false).withIcon(R.drawable.ic_drawer_test_24dp),
                        DividerDrawerItem(),
                        PrimaryDrawerItem().withIdentifier(6).withName(R.string.start_activity_drawer_rate_app).withSelectable(false).withIcon(R.drawable.ic_drawer_rateapp_24dp),
                        PrimaryDrawerItem().withIdentifier(7).withName(R.string.start_activity_drawer_feedback).withSelectable(false).withIcon(R.drawable.ic_drawer_feedback_24dp),
                        PrimaryDrawerItem().withIdentifier(8).withName(R.string.start_activity_drawer_sources).withSelectable(false).withIcon(R.drawable.ic_drawer_sources_24dp)
                )
                .addStickyDrawerItems(
                        PrimaryDrawerItem().withIdentifier(9).withName(R.string.start_activity_drawer_exit).withIcon(R.drawable.ic_drawer_exit_24pd)
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

                    if (drawerItem.identifier == 7L) {
                        val emailIntent = Intent(Intent.ACTION_SENDTO)
                                .setData(Uri.Builder().scheme("mailto").build())
                                .putExtra(Intent.EXTRA_EMAIL, arrayOf("Dmitriy Krivoruchko <dkrivoruchko@gmail.com>"))
                                .putExtra(Intent.EXTRA_SUBJECT, "Screen Stream Feedback")
                        startActivity(Intent.createChooser(emailIntent, getString(R.string.start_activity_email_chooser_header)))
                    }

                    if (drawerItem.identifier == 8L) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/dkrivoruchko/ScreenStream")))
                    }

                    if (drawerItem.identifier == 9L) fromEvents.onNext(StartActivityView.FromEvent.AppExit())
                    true
                }
                .build()

        drawer.deselect()

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
        }
    }

    override fun onResume() {
        super.onResume()
        fromEvents.onNext(StartActivityView.FromEvent.CurrentInterfacesRequest())
        fromEvents.onNext(StartActivityView.FromEvent.GetError())
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
        super.onDestroy()
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onActivityResult: $requestCode")
        when (requestCode) {
            REQUEST_CODE_SCREEN_CAPTURE -> {
                if (Activity.RESULT_OK != resultCode) {
                    fromEvents.onNext(StartActivityView.FromEvent.Error(SecurityException()))
                    if (BuildConfig.DEBUG_MODE) Log.w(TAG, "onActivityResult: Screen Cast permission denied")
                    return
                }

                if (null == data) {
                    if (BuildConfig.DEBUG_MODE) Log.e(TAG, "onActivityResult ERROR: data = null")
                    val error = IllegalStateException("onActivityResult: data = null")
                    Crashlytics.logException(error)
                    fromEvents.onNext(StartActivityView.FromEvent.Error(error))
                    return
                }
                startService(ForegroundService.getStartStreamIntent(applicationContext, data))
            }
        }
    }

    // Private methods
    private fun setStreamRunning(running: Boolean) {
        toggleButtonStartStop.isChecked = running
        if (settings.enablePin && settings.hidePinOnStart) {
            if (running) textViewPinValue.setText(R.string.start_activity_pin_asterisks)
            else textViewPinValue.text = settings.currentPin
        }
    }

    private fun showServerAddresses(interfaceList: List<ForegroundServiceView.Interface>, serverPort: Int) {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] showServerAddresses")

        linearLayoutServerAddressList.removeAllViews()
        val layoutInflater = LayoutInflater.from(this)
        if (interfaceList.isEmpty()) {
            val addressView = layoutInflater.inflate(R.layout.server_address, null)
            with(addressView) {
                textViewInterfaceName.text = ""
                textViewInterfaceAddress.text = getString(R.string.start_activity_no_address)
                textViewInterfaceAddress.setTextColor(ContextCompat.getColor(context, R.color.colorAccent))
            }
            linearLayoutServerAddressList.addView(addressView)
        } else {
            for ((name, address) in interfaceList) {
                val addressView = layoutInflater.inflate(R.layout.server_address, null)
                with(addressView) {
                    textViewInterfaceName.text = "$name:"
                    textViewInterfaceAddress.text = "http://$address:$serverPort"
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