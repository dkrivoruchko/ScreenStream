package info.dvkr.screenstream.ui


import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.support.v4.content.ContextCompat
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.mikepenz.materialdrawer.Drawer
import com.mikepenz.materialdrawer.DrawerBuilder
import com.mikepenz.materialdrawer.model.DividerDrawerItem
import com.mikepenz.materialdrawer.model.PrimaryDrawerItem
import com.tapadoo.alerter.Alerter
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.presenter.BaseView
import info.dvkr.screenstream.data.presenter.start.StartPresenter
import info.dvkr.screenstream.data.presenter.start.StartView
import info.dvkr.screenstream.domain.eventbus.EventBus
import info.dvkr.screenstream.domain.settings.Settings
import info.dvkr.screenstream.domain.utils.Utils
import info.dvkr.screenstream.service.FgService
import kotlinx.android.synthetic.main.activity_start.*
import kotlinx.android.synthetic.main.server_address.view.*
import org.koin.android.architecture.ext.getViewModel
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.net.BindException


class StartActivity : BaseActivity(), StartView {

    companion object {
        private const val REQUEST_CODE_SCREEN_CAPTURE = 10
        private const val DIALOG_SCREEN_CAPTURE_PERMISSION_TAG = "DIALOG_SCREEN_CAPTURE_PERMISSION_TAG"

        private const val EXTRA_DATA = "EXTRA_DATA"
        const val ACTION_START_STREAM = "ACTION_START_STREAM"
        const val ACTION_STOP_STREAM = "ACTION_STOP_STREAM"
        const val ACTION_EXIT = "ACTION_EXIT"
        const val ACTION_ERROR = "ACTION_ERROR"

        fun getStartIntent(context: Context): Intent = Intent(context, StartActivity::class.java)

        fun getStartIntent(context: Context, action: String): Intent {
            return Intent(context, StartActivity::class.java)
                .putExtra(EXTRA_DATA, action)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }

    private val settings: Settings by inject()
    private val presenter: StartPresenter by lazy { getViewModel<StartPresenter>() }

    private lateinit var drawer: Drawer
    private var canStart: Boolean = true

    private val projectionManager: MediaProjectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val clipboard: ClipboardManager by lazy {
        getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    override fun toEvent(toEvent: BaseView.BaseToEvent) = runOnUiThread {
        Timber.d("[${Utils.getLogPrefix(this)}] toEvent: ${toEvent.javaClass.simpleName}")

        when (toEvent) {
            is StartView.ToEvent.TryToStart -> {
                try {
                    startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_CAPTURE)
                } catch (ex: ActivityNotFoundException) {
                    presenter.offer(StartView.FromEvent.Error(ex))
                }
            }

            is StartView.ToEvent.OnStreamStartStop -> {
                setStreamRunning(toEvent.running)
                if (toEvent.running && settings.minimizeOnStream)
                    try {
                        startActivity(
                            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK
                            )
                        )
                    } catch (ex: ActivityNotFoundException) {
                        Timber.e(
                            ex,
                            "StartView.ToEvent.OnStreamStartStop: minimizeOnStream: ActivityNotFoundException"
                        )
                    }
            }

            is StartView.ToEvent.ResizeFactor -> showResizeFactor(toEvent.value)
            is StartView.ToEvent.EnablePin -> showEnablePin(toEvent.value)
            is StartView.ToEvent.SetPin -> textViewPinValue.text = toEvent.value
            is StartView.ToEvent.StreamRunning -> setStreamRunning(toEvent.running)

            is BaseView.BaseToEvent.Error -> {
                canStart = true
                toEvent.error?.let {
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

                        is NoSuchElementException -> {
                            canStart = false
                            alerter.setTitle(R.string.start_activity_alert_title_error_network)
                                .setText(R.string.start_activity_error_ip_address_not_found)
                        }

                        is BindException -> {
                            canStart = false
                            alerter.setTitle(R.string.start_activity_alert_title_error_network)
                            val msg = it.message?.drop(13)
                                    ?: getString(R.string.start_activity_error_network_unknown)
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

            is StartView.ToEvent.CurrentClients -> {
                val clientsCount = toEvent.clientsList.filter { !it.disconnected }.count()
                textViewConnectedClients.text =
                        getString(R.string.start_activity_connected_clients).format(clientsCount)
            }

            is StartView.ToEvent.CurrentInterfaces -> {
                showServerAddresses(toEvent.interfaceList, settings.severPort)
            }

            is StartView.ToEvent.TrafficPoint -> {
                val mbit = (toEvent.trafficPoint.bytes * 8).toDouble() / 1024 / 1024
                textViewCurrentTraffic.text = getString(R.string.start_activity_current_traffic).format(mbit)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start)

        setSupportActionBar(toolbarStart)
        supportActionBar?.setTitle(R.string.start_activity_name)

        startService(FgService.getIntent(applicationContext, FgService.ACTION_INIT))
        toggleButtonStartStop.isEnabled = false
        textViewConnectedClients.text = getString(R.string.start_activity_connected_clients).format(0)
        textViewCurrentTraffic.text = getString(R.string.start_activity_current_traffic).format(0f)

        toggleButtonStartStop.setOnClickListener { _ ->
            if (toggleButtonStartStop.isChecked) {
                toggleButtonStartStop.isChecked = false
                if (!canStart) {
                    presenter.offer(StartView.FromEvent.GetError)
                    return@setOnClickListener
                }
                toggleButtonStartStop.isEnabled = false
                presenter.offer(StartView.FromEvent.TryStartStream)
            } else {
                toggleButtonStartStop.isChecked = true
                toggleButtonStartStop.isEnabled = false
                presenter.offer(StartView.FromEvent.StopStream)
            }
        }

        presenter.attach(this)

        drawer = DrawerBuilder()
            .withActivity(this)
            .withToolbar(toolbarStart)
            .withHeader(R.layout.activity_start_drawer_header)
            .withHasStableIds(true)
            .addDrawerItems(
                PrimaryDrawerItem().withIdentifier(1).withName(R.string.start_activity_drawer_main).withSelectable(
                    false
                ).withIcon(R.drawable.ic_drawer_main_24dp),
                PrimaryDrawerItem().withIdentifier(2).withName(R.string.start_activity_drawer_traffic_clients).withSelectable(
                    false
                ).withIcon(R.drawable.ic_drawer_connected_24dp),
                PrimaryDrawerItem().withIdentifier(3).withName(R.string.start_activity_drawer_settings).withSelectable(
                    false
                ).withIcon(R.drawable.ic_drawer_settings_24dp),
                DividerDrawerItem(),
                PrimaryDrawerItem().withIdentifier(6).withName(R.string.start_activity_drawer_rate_app).withSelectable(
                    false
                ).withIcon(R.drawable.ic_drawer_rateapp_24dp),
                PrimaryDrawerItem().withIdentifier(7).withName(R.string.start_activity_drawer_about).withSelectable(
                    false
                ).withIcon(R.drawable.ic_drawer_about_24dp)
            )
            .addStickyDrawerItems(
                PrimaryDrawerItem().withIdentifier(8).withName(R.string.start_activity_drawer_exit).withIcon(
                    R.drawable.ic_drawer_exit_24dp
                )
            )
            .withOnDrawerItemClickListener { _, _, drawerItem ->
                if (drawerItem.identifier == 1L) if (drawer.isDrawerOpen) drawer.closeDrawer()
                if (drawerItem.identifier == 2L) startActivity(ClientsActivity.getStartIntent(this))
                if (drawerItem.identifier == 3L) startActivity(SettingsActivity.getStartIntent(this))

                if (drawerItem.identifier == 6L) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
                    } catch (ex: ActivityNotFoundException) {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
                            )
                        )
                    }
                }

                if (drawerItem.identifier == 7L) startActivity(AboutActivity.getStartIntent(this))
                if (drawerItem.identifier == 8L) presenter.offer(StartView.FromEvent.AppExit)
                true
            }
            .build()

        drawer.deselect()

        showResizeFactor(settings.resizeFactor)
        showEnablePin(settings.enablePin)
        textViewPinValue.text = settings.currentPin

        onNewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val action = intent.getStringExtra(EXTRA_DATA)
        Timber.i("[${Utils.getLogPrefix(this)}] onNewIntent: action = $action")
        action != null || return

        intent.removeExtra(EXTRA_DATA)
        this.intent = intent

        when (action) {
            ACTION_START_STREAM -> {
                canStart || return
                toggleButtonStartStop.isEnabled = false
                presenter.offer(StartView.FromEvent.TryStartStream)
            }

            ACTION_STOP_STREAM -> {
                toggleButtonStartStop.isEnabled = false
                presenter.offer(StartView.FromEvent.StopStream)
            }

            ACTION_EXIT -> presenter.offer(StartView.FromEvent.AppExit)
        }
    }

    override fun onResume() {
        super.onResume()
        presenter.offer(StartView.FromEvent.StreamRunningRequest)
        presenter.offer(StartView.FromEvent.CurrentInterfacesRequest)
        presenter.offer(StartView.FromEvent.GetError)
    }

    override fun onBackPressed() {
        if (drawer.isDrawerOpen) drawer.closeDrawer() else drawer.openDrawer()
    }

    override fun onStop() {
        if (drawer.isDrawerOpen) drawer.closeDrawer()
        super.onStop()
    }

    override fun onDestroy() {
        presenter.detach()
        super.onDestroy()
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Timber.w("[${Utils.getLogPrefix(this)}] onActivityResult: $requestCode")

        when (requestCode) {
            REQUEST_CODE_SCREEN_CAPTURE -> {
                if (Activity.RESULT_OK != resultCode) {
                    toggleButtonStartStop.isEnabled = true

                    NotificationDialog.newInstance(
                        DIALOG_SCREEN_CAPTURE_PERMISSION_TAG,
                        titleText = getString(R.string.start_activity_cast_permission_required_title),
                        messageText = getString(R.string.start_activity_cast_permission_required),
                        positiveButtonText = getString(android.R.string.ok),
                        isCancelable = false
                    )
                        .show(fragmentManager, DIALOG_SCREEN_CAPTURE_PERMISSION_TAG)

                    Timber.w("onActivityResult: Screen Cast permission denied")
                    return
                }

                if (null == data) {
                    toggleButtonStartStop.isEnabled = true
                    val error = IllegalStateException("onActivityResult: data = null")
                    Timber.e(error, "onActivityResult ERROR: data = null")
                    presenter.offer(StartView.FromEvent.Error(error))
                    return
                }
                startService(FgService.getStartStreamIntent(applicationContext, data))
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

    private fun showServerAddresses(interfaceList: List<EventBus.Interface>, serverPort: Int) {
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
                        clipboard.primaryClip = ClipData.newPlainText(
                            textViewInterfaceAddress.text, textViewInterfaceAddress.text
                        )
                        Toast.makeText(applicationContext, R.string.start_activity_copied, Toast.LENGTH_LONG).show()
                    }
                }
                linearLayoutServerAddressList.addView(addressView)
            }
        }
    }

    private fun showResizeFactor(resizeFactor: Int) {
        Timber.i("[${Utils.getLogPrefix(this)}] showResizeFactor")

        val defaultDisplay =
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        val screenSize = Point()
        defaultDisplay.getSize(screenSize)
        val scale = resizeFactor / 100f
        textViewResizeFactor.text = getString(R.string.start_activity_resize_factor) +
                " $resizeFactor%: ${(screenSize.x * scale).toInt()}x${(screenSize.y * scale).toInt()}"
    }

    private fun showEnablePin(enablePin: Boolean) {
        Timber.i("[${Utils.getLogPrefix(this)}] showEnablePin")

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