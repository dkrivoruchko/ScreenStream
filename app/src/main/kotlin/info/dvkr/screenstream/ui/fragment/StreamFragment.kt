package info.dvkr.screenstream.ui.fragment

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.customview.customView
import com.afollestad.materialdialogs.lifecycle.lifecycleOwner
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.model.FatalError
import info.dvkr.screenstream.data.model.FixableError
import info.dvkr.screenstream.data.model.HttpClient
import info.dvkr.screenstream.data.other.*
import info.dvkr.screenstream.data.settings.SettingsReadOnly
import info.dvkr.screenstream.service.ServiceMessage
import info.dvkr.screenstream.service.helper.IntentAction
import info.dvkr.screenstream.ui.activity.ServiceActivity
import kotlinx.android.extensions.LayoutContainer
import kotlinx.android.synthetic.main.fragment_stream.*
import kotlinx.android.synthetic.main.item_client.*
import kotlinx.android.synthetic.main.item_device_address.view.*
import org.koin.android.ext.android.inject

class StreamFragment : Fragment() {

    private val colorAccent by lazy { ContextCompat.getColor(requireContext(), R.color.colorAccent) }
    private val clipboard: ClipboardManager? by lazy {
        ContextCompat.getSystemService(requireContext(), ClipboardManager::class.java)
    }

    private val settingsReadOnly: SettingsReadOnly by inject()
    private var httpClientAdapter: HttpClientAdapter? = null
    private var errorPrevious: AppError? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_stream, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tv_fragment_stream_traffic_header.text = getString(R.string.stream_fragment_current_traffic).run {
            format(0.0).setColorSpan(colorAccent, indexOf('%'))
        }

        tv_fragment_stream_clients_header.text = getString(R.string.stream_fragment_connected_clients).run {
            format(0).setColorSpan(colorAccent, indexOf('%'))
        }

        with(rv_fragment_stream_clients) {
            itemAnimator = DefaultItemAnimator()
            httpClientAdapter = HttpClientAdapter().apply { setHasStableIds(true) }
            adapter = httpClientAdapter
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        httpClientAdapter = null
    }

    override fun onStart() {
        super.onStart()
        XLog.d(getLog("onStart", "Invoked"))

        (requireActivity() as ServiceActivity).getServiceMessageLiveData()
            .observe(this, Observer<ServiceMessage> { serviceMessage ->
                when (serviceMessage) {
                    is ServiceMessage.ServiceState -> onServiceStateMessage(serviceMessage)
                    is ServiceMessage.Clients -> onClientsMessage(serviceMessage)
                    is ServiceMessage.TrafficHistory -> onTrafficHistoryMessage(serviceMessage)
                }
            })

        IntentAction.GetServiceState.sendToAppService(requireContext())
    }

    private fun onServiceStateMessage(serviceMessage: ServiceMessage.ServiceState) {
        // Interfaces
        ll_fragment_stream_addresses.removeAllViews()
        if (serviceMessage.netInterfaces.isEmpty()) {
            with(layoutInflater.inflate(R.layout.item_device_address, ll_fragment_stream_addresses, false)) {
                tv_item_device_address_name.text = ""
                tv_item_device_address.setText(R.string.stream_fragment_no_address)
                tv_item_device_address.setTextColor(ContextCompat.getColor(requireContext(), R.color.textColorPrimary))
                ll_fragment_stream_addresses.addView(this)
            }
        } else {
            serviceMessage.netInterfaces.sortedBy { it.address.asString() }.forEach { netInterface ->
                with(layoutInflater.inflate(R.layout.item_device_address, ll_fragment_stream_addresses, false)) {
                    tv_item_device_address_name.text = getString(R.string.stream_fragment_interface, netInterface.name)

                    val fullAddress = "http://${netInterface.address.asString()}:${settingsReadOnly.severPort}"
                    tv_item_device_address.text = fullAddress.setUnderlineSpan()
                    tv_item_device_address.setOnClickListener { openInBrowser(fullAddress) }
                    iv_item_device_address_open_external.setOnClickListener { openInBrowser(fullAddress) }
                    iv_item_device_address_copy.setOnClickListener {
                        clipboard?.setPrimaryClip(
                            ClipData.newPlainText(tv_item_device_address.text, tv_item_device_address.text)
                        )
                        Toast.makeText(
                            requireContext().applicationContext, R.string.stream_fragment_copied, Toast.LENGTH_LONG
                        ).show()
                    }
                    iv_item_device_address_share.setOnClickListener { shareAddress(fullAddress) }
                    iv_item_device_address_qr.setOnClickListener { showQrCode(fullAddress) }
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

        showError(serviceMessage.appError)
    }

    private fun onClientsMessage(serviceMessage: ServiceMessage.Clients) {
        val clientsCount = serviceMessage.clients.filter { it.isDisconnected.not() }.count()
        tv_fragment_stream_clients_header.text = getString(R.string.stream_fragment_connected_clients).run {
            format(clientsCount).setColorSpan(colorAccent, indexOf('%'))
        }
        httpClientAdapter?.submitList(serviceMessage.clients)
    }

    private fun onTrafficHistoryMessage(serviceMessage: ServiceMessage.TrafficHistory) {
        tv_fragment_stream_traffic_header.text = getString(R.string.stream_fragment_current_traffic).run {
            format(serviceMessage.trafficHistory.last().bytes.bytesToMbit()).setColorSpan(colorAccent, indexOf('%'))
        }

        traffic_graph_fragment_stream.setDataPoints(
            serviceMessage.trafficHistory.map { Pair(it.time, it.bytes.bytesToMbit()) }
        )
    }

    private fun openInBrowser(fullAddress: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fullAddress)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (ex: ActivityNotFoundException) {
            Toast.makeText(
                requireContext().applicationContext, R.string.stream_fragment_no_web_browser_found, Toast.LENGTH_LONG
            ).show()
            XLog.w(getLog("openInBrowser", ex.toString()))
        } catch (ex: SecurityException) {
            Toast.makeText(
                requireContext().applicationContext, R.string.stream_fragment_external_app_error, Toast.LENGTH_LONG
            ).show()
            XLog.w(getLog("openInBrowser", ex.toString()))
        }
    }

    private fun shareAddress(fullAddress: String) {
        val sharingIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, fullAddress)
        }
        startActivity(Intent.createChooser(sharingIntent, getString(R.string.stream_fragment_share_address)))
    }

    private fun showQrCode(fullAddress: String) {
        fullAddress.getQRBitmap(resources.getDimensionPixelSize(R.dimen.fragment_stream_qrcode_size))?.let { qrBitmap ->
            if (viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                val imageView = AppCompatImageView(requireContext()).apply { setImageBitmap(qrBitmap) }
                MaterialDialog(requireActivity())
                    .lifecycleOwner(viewLifecycleOwner)
                    .customView(view = imageView, noVerticalPadding = true)
                    .maxWidth(R.dimen.fragment_stream_qrcode_size)
                    .show()
            }
        }
    }

    private fun showError(appError: AppError?) {
        errorPrevious != appError || return

        if (appError == null) {
            tv_fragment_stream_error.visibility = View.GONE
        } else {
            XLog.d(getLog("showError", appError.toString()))
            tv_fragment_stream_error.text = when (appError) {
                is FixableError.AddressInUseException -> getString(R.string.error_port_in_use)
                is FixableError.CastSecurityException -> getString(R.string.error_invalid_media_projection)
                is FixableError.AddressNotFoundException -> getString(R.string.error_ip_address_not_found)
                is FatalError.BitmapFormatException -> getString(R.string.error_wrong_image_format)
                else -> appError.toString()
            }
            tv_fragment_stream_error.visibility = View.VISIBLE
        }

        errorPrevious = appError
    }

    private class HttpClientAdapter : ListAdapter<HttpClient, HttpClientViewHolder>(
        object : DiffUtil.ItemCallback<HttpClient>() {
            override fun areItemsTheSame(oldItem: HttpClient, newItem: HttpClient): Boolean = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: HttpClient, newItem: HttpClient): Boolean = oldItem == newItem
        }
    ) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            HttpClientViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_client, parent, false))

        override fun getItemId(position: Int): Long = getItem(position).id

        override fun onBindViewHolder(holder: HttpClientViewHolder, position: Int) = holder.bind(getItem(position))
    }

    private class HttpClientViewHolder(override val containerView: View) :
        RecyclerView.ViewHolder(containerView), LayoutContainer {

        private val textColorPrimary by lazy { ContextCompat.getColor(containerView.context, R.color.textColorPrimary) }
        private val colorError by lazy { ContextCompat.getColor(containerView.context, R.color.colorError) }
        private val colorAccent by lazy { ContextCompat.getColor(containerView.context, R.color.colorAccent) }

        fun bind(product: HttpClient) = with(product) {
            tv_client_item_address.text = clientAddress
            with(tv_client_item_status) {
                when {
                    isDisconnected -> {
                        setText(R.string.stream_fragment_client_disconnected)
                        setTextColor(textColorPrimary)
                    }
                    isSlowConnection -> {
                        setText(R.string.stream_fragment_client_slow_network)
                        setTextColor(colorError)
                    }
                    else -> {
                        setText(R.string.stream_fragment_client_connected)
                        setTextColor(colorAccent)
                    }
                }
            }
        }
    }
}