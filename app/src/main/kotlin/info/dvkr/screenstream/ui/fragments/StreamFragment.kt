package info.dvkr.screenstream.ui.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import com.jjoe64.graphview.DefaultLabelFormatter
import com.jjoe64.graphview.GridLabelRenderer
import com.jjoe64.graphview.series.DataPoint
import com.jjoe64.graphview.series.LineGraphSeries
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.other.getTag
import info.dvkr.screenstream.data.other.setColorSpan
import info.dvkr.screenstream.data.settings.SettingsReadOnly
import info.dvkr.screenstream.service.AppService
import info.dvkr.screenstream.service.ServiceMessage
import info.dvkr.screenstream.ui.activity.BaseActivity
import info.dvkr.screenstream.ui.router.FragmentRouter
import kotlinx.android.synthetic.main.fragment_stream.*
import kotlinx.android.synthetic.main.item_client.view.*
import kotlinx.android.synthetic.main.item_device_address.view.*
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.text.NumberFormat

class StreamFragment : Fragment() {

    companion object {
        fun getFragmentCreator() = object : FragmentRouter.FragmentCreator {
            override fun getMenuItemId(): Int = R.id.menu_stream_fragment
            override fun getTag(): String = StreamFragment::class.java.name
            override fun newInstance(): Fragment = StreamFragment()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_stream, container, false)

    private val textColorPrimary by lazy { ContextCompat.getColor(requireContext(), R.color.textColorPrimary) }
    private val colorAccent by lazy { ContextCompat.getColor(requireContext(), R.color.colorAccent) }
    private val colorError by lazy { ContextCompat.getColor(requireContext(), R.color.colorErrorBackground) }

    private val clipboard: ClipboardManager? by lazy {
        ContextCompat.getSystemService(requireContext(), ClipboardManager::class.java)
    }

    private val settingsReadOnly: SettingsReadOnly by inject()
    private lateinit var lineGraphSeries: LineGraphSeries<DataPoint>
    private var isViewCreated = true
    private var maxY: Double = 0.0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Timber.tag(getTag("onViewCreated")).w("Invoked")
        isViewCreated = true

        tv_fragment_stream_traffic_header.text = getString(R.string.stream_fragment_current_traffic).run {
            format(0.0).setColorSpan(colorAccent, indexOf('%'))
        }

        tv_fragment_stream_clients_header.text = getString(R.string.stream_fragment_connected_clients).run {
            format(0).setColorSpan(colorAccent, indexOf('%'))
        }
    }

    override fun onStart() {
        super.onStart()
        Timber.tag(getTag("onStart")).w("Invoked")

        (requireActivity() as BaseActivity).getServiceMessageLiveData()
            .observe(this, Observer<ServiceMessage> { serviceMessage ->
                when (serviceMessage) {
                    is ServiceMessage.ServiceState -> onServiceStateMessage(serviceMessage)
                    is ServiceMessage.Clients -> onClientsMessage(serviceMessage)
                    is ServiceMessage.TrafficHistory -> onTrafficHistoryMessage(serviceMessage)
                }
            })

        el_fragment_stream_traffic.collapse(false)
        el_fragment_stream_clients.collapse(false)

        AppService.startForegroundService(requireContext(), AppService.IntentAction.GetServiceState)
    }

    private fun onServiceStateMessage(serviceMessage: ServiceMessage.ServiceState) {
        // Interfaces
        ll_fragment_stream_addresses.removeAllViews()
        if (serviceMessage.netInterfaces.isEmpty()) {
            with(layoutInflater.inflate(R.layout.item_device_address, ll_fragment_stream_addresses, false)) {
                tv_item_device_address_name.text = ""
                tv_item_device_address.setText(R.string.stream_fragment_no_address)
                ll_fragment_stream_addresses.addView(this)
            }
        } else {
            serviceMessage.netInterfaces.forEach { netInterface ->
                with(layoutInflater.inflate(R.layout.item_device_address, ll_fragment_stream_addresses, false)) {
                    tv_item_device_address_name.text = getString(R.string.stream_fragment_interface, netInterface.name)

                    val fullAddress = "http://${netInterface.address.hostAddress}:${settingsReadOnly.severPort}"
                    tv_item_device_address.text = fullAddress
                    iv_item_device_address_open_external.setOnClickListener {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(fullAddress)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    iv_item_device_address_copy.setOnClickListener {
                        clipboard?.primaryClip =
                                ClipData.newPlainText(tv_item_device_address.text, tv_item_device_address.text)
                        Toast.makeText(
                            requireContext().applicationContext,
                            R.string.stream_fragment_copied,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    ll_fragment_stream_addresses.addView(this)
                }
            }
        }

        // Hide pin on Start
        if (settingsReadOnly.enablePin) {
            val pinText = if (serviceMessage.isStreaming && settingsReadOnly.hidePinOnStart)
                getString(R.string.stream_fragment_pin, "****")
            else
                getString(R.string.stream_fragment_pin, settingsReadOnly.pin)

            tv_fragment_stream_pin.text = pinText.setColorSpan(colorAccent, pinText.length - 4)
        } else {
            tv_fragment_stream_pin.setText(R.string.stream_fragment_pin_disabled)
        }
    }

    private fun onClientsMessage(serviceMessage: ServiceMessage.Clients) {
        val clientsCount = serviceMessage.clients.filter { it.isDisconnected.not() }.count()
        tv_fragment_stream_clients_header.text = getString(R.string.stream_fragment_connected_clients).run {
            format(clientsCount).setColorSpan(colorAccent, indexOf('%'))
        }

        ll_fragment_stream_client_list.removeAllViews()
        serviceMessage.clients.forEach { client ->
            with(layoutInflater.inflate(R.layout.item_client, ll_fragment_stream_clients, false)) {
                tv_client_item_address.text = client.clientAddress.toString().drop(1)
                when {
                    client.isDisconnected -> tv_client_item_status.setText(R.string.stream_fragment_client_disconnected)
                    client.isSlowConnection -> {
                        tv_client_item_status.setText(R.string.stream_fragment_client_slow_network)
                        tv_client_item_status.setTextColor(colorError)
                    }
                    else -> {
                        tv_client_item_status.setText(R.string.stream_fragment_client_connected)
                        tv_client_item_status.setTextColor(colorAccent)
                    }
                }
                ll_fragment_stream_client_list.addView(this)
            }
        }
    }

    private fun onTrafficHistoryMessage(serviceMessage: ServiceMessage.TrafficHistory) {
        tv_fragment_stream_traffic_header.text = getString(R.string.stream_fragment_current_traffic).run {
            format(serviceMessage.trafficHistory.last().bytes.toMbit()).setColorSpan(colorAccent, indexOf('%'))
        }

        with(graph_fragment_stream_traffic) {
            if (isViewCreated) {
                val arrayOfDataPoints = serviceMessage.trafficHistory
                    .map { DataPoint(it.time.toDouble(), it.bytes.toMbit()) }
                    .toTypedArray()

                removeAllSeries()
                lineGraphSeries = LineGraphSeries(arrayOfDataPoints).apply {
                    color = colorAccent
                    thickness = 6
                }
                addSeries(lineGraphSeries)

                viewport.isXAxisBoundsManual = true
                viewport.setMinX(arrayOfDataPoints.first().x)
                viewport.setMaxX(arrayOfDataPoints.last().x)

                viewport.isYAxisBoundsManual = true
                maxY = Math.max(arrayOfDataPoints.map { it.y }.max()?.times(1.2) ?: 0.0, 1.1)
                viewport.setMinY(maxY * -0.1)
                viewport.setMaxY(maxY)

                val numberFormat = NumberFormat.getInstance().apply {
                    minimumFractionDigits = 1
                    maximumFractionDigits = 1
                    minimumIntegerDigits = 1
                }
                gridLabelRenderer.labelFormatter = DefaultLabelFormatter(numberFormat, numberFormat)
                gridLabelRenderer.verticalLabelsColor = textColorPrimary
                gridLabelRenderer.isHorizontalLabelsVisible = false
                gridLabelRenderer.gridStyle = GridLabelRenderer.GridStyle.HORIZONTAL

                isViewCreated = false
            } else {
                val currentTraffic = serviceMessage.trafficHistory.last().bytes.toMbit()
                lineGraphSeries.appendData(
                    DataPoint(serviceMessage.trafficHistory.last().time.toDouble(), currentTraffic), true, 60
                )
                maxY = Math.max(maxY, currentTraffic)
                viewport.setMinY(maxY * -0.1)
                viewport.setMaxY(maxY)
            }
        }
    }

    private fun Long.toMbit() = (this * 8).toDouble() / 1024 / 1024
}