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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
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
import info.dvkr.screenstream.data.other.asString
import info.dvkr.screenstream.data.other.bytesToMbit
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.data.other.getQRBitmap
import info.dvkr.screenstream.data.other.setColorSpan
import info.dvkr.screenstream.data.other.setUnderlineSpan
import info.dvkr.screenstream.data.settings.SettingsReadOnly
import info.dvkr.screenstream.databinding.FragmentStreamBinding
import info.dvkr.screenstream.databinding.ItemClientBinding
import info.dvkr.screenstream.databinding.ItemDeviceAddressBinding
import info.dvkr.screenstream.service.ServiceMessage
import info.dvkr.screenstream.service.helper.IntentAction
import info.dvkr.screenstream.ui.activity.ServiceActivity
import info.dvkr.screenstream.ui.viewBinding
import kotlinx.coroutines.flow.first
import org.koin.android.ext.android.inject

class StreamFragment : AdFragment(R.layout.fragment_stream) {

    private val colorAccent by lazy { ContextCompat.getColor(requireContext(), R.color.colorAccent) }
    private val clipboard: ClipboardManager? by lazy {
        ContextCompat.getSystemService(requireContext(), ClipboardManager::class.java)
    }

    private val settingsReadOnly: SettingsReadOnly by inject()
    private var httpClientAdapter: HttpClientAdapter? = null
    private var errorPrevious: AppError? = null

    private val binding by viewBinding { fragment -> FragmentStreamBinding.bind(fragment.requireView()) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadAdOnViewCreated(binding.flFragmentStreamAdViewContainer)

        binding.tvFragmentStreamTrafficHeader.text = getString(R.string.stream_fragment_current_traffic).run {
            format(0.0).setColorSpan(colorAccent, indexOf('%'))
        }

        binding.tvFragmentStreamClientsHeader.text = getString(R.string.stream_fragment_connected_clients).run {
            format(0).setColorSpan(colorAccent, indexOf('%'))
        }

        with(binding.rvFragmentStreamClients) {
            itemAnimator = DefaultItemAnimator()
            httpClientAdapter = HttpClientAdapter().apply { setHasStableIds(true) }
            adapter = httpClientAdapter
        }
    }

    override fun onStart() {
        super.onStart()
        XLog.d(getLog("onStart", "Invoked"))

        (requireActivity() as ServiceActivity).getServiceMessageLiveData()
            .observe(this) { serviceMessage ->
                when (serviceMessage) {
                    is ServiceMessage.ServiceState ->
                        viewLifecycleOwner.lifecycleScope.launchWhenStarted { onServiceStateMessage(serviceMessage) }
                    is ServiceMessage.Clients -> onClientsMessage(serviceMessage)
                    is ServiceMessage.TrafficHistory -> onTrafficHistoryMessage(serviceMessage)
                    else -> Unit
                }
            }

        IntentAction.GetServiceState.sendToAppService(requireContext())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        httpClientAdapter = null
    }

    private suspend fun onServiceStateMessage(serviceMessage: ServiceMessage.ServiceState) {
        // Interfaces
        binding.llFragmentStreamAddresses.removeAllViews()
        if (serviceMessage.netInterfaces.isEmpty()) {
            with(ItemDeviceAddressBinding.inflate(layoutInflater, binding.llFragmentStreamAddresses, false)) {
                tvItemDeviceAddressName.text = ""
                tvItemDeviceAddress.setText(R.string.stream_fragment_no_address)
                tvItemDeviceAddress.setTextColor(ContextCompat.getColor(requireContext(), R.color.textColorPrimary))
                binding.llFragmentStreamAddresses.addView(this.root)
            }
        } else {
            serviceMessage.netInterfaces.sortedBy { it.address.asString() }.forEach { netInterface ->
                with(ItemDeviceAddressBinding.inflate(layoutInflater, binding.llFragmentStreamAddresses, false)) {
                    tvItemDeviceAddressName.text = getString(R.string.stream_fragment_interface, netInterface.name)

                    val fullAddress =
                        "http://${netInterface.address.asString()}:${settingsReadOnly.serverPortFlow.first()}"
                    tvItemDeviceAddress.text = fullAddress.setUnderlineSpan()
                    tvItemDeviceAddress.setOnClickListener { openInBrowser(fullAddress) }
                    ivItemDeviceAddressOpenExternal.setOnClickListener { openInBrowser(fullAddress) }
                    ivItemDeviceAddressCopy.setOnClickListener {
                        clipboard?.setPrimaryClip(
                            ClipData.newPlainText(tvItemDeviceAddress.text, tvItemDeviceAddress.text)
                        )
                        Toast.makeText(requireContext(), R.string.stream_fragment_copied, Toast.LENGTH_LONG).show()
                    }
                    ivItemDeviceAddressShare.setOnClickListener { shareAddress(fullAddress) }
                    ivItemDeviceAddressQr.setOnClickListener { showQrCode(fullAddress) }
                    binding.llFragmentStreamAddresses.addView(this.root)
                }
            }
        }

        // Hide pin on Start
        if (settingsReadOnly.enablePinFlow.first()) {
            if (serviceMessage.isStreaming && settingsReadOnly.hidePinOnStartFlow.first()) {
                val pinText = getString(R.string.stream_fragment_pin, "*")
                binding.tvFragmentStreamPin.text = pinText.setColorSpan(colorAccent, pinText.length - 1)
            } else {
                val pinText = getString(R.string.stream_fragment_pin, settingsReadOnly.pinFlow.first())
                binding.tvFragmentStreamPin.text =
                    pinText.setColorSpan(colorAccent, pinText.length - settingsReadOnly.pinFlow.first().length)
            }
        } else {
            binding.tvFragmentStreamPin.setText(R.string.stream_fragment_pin_disabled)
        }

        showError(serviceMessage.appError)
    }

    private fun onClientsMessage(serviceMessage: ServiceMessage.Clients) {
        val clientsCount = serviceMessage.clients.count { it.isDisconnected.not() }
        binding.tvFragmentStreamClientsHeader.text = getString(R.string.stream_fragment_connected_clients).run {
            format(clientsCount).setColorSpan(colorAccent, indexOf('%'))
        }
        httpClientAdapter?.submitList(serviceMessage.clients)
    }

    private fun onTrafficHistoryMessage(serviceMessage: ServiceMessage.TrafficHistory) {
        binding.tvFragmentStreamTrafficHeader.text = getString(R.string.stream_fragment_current_traffic).run {
            val lastTrafficPoint = serviceMessage.trafficHistory.lastOrNull() ?: return@run "0"
            format(lastTrafficPoint.bytes.bytesToMbit()).setColorSpan(colorAccent, indexOf('%'))
        }

        binding.trafficGraphFragmentStream.setDataPoints(
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
            binding.tvFragmentStreamError.visibility = View.GONE
        } else {
            XLog.d(getLog("showError", appError.toString()))
            binding.tvFragmentStreamError.text = when (appError) {
                is FixableError.AddressInUseException -> getString(R.string.error_port_in_use)
                is FixableError.CastSecurityException -> getString(R.string.error_invalid_media_projection)
                is FixableError.AddressNotFoundException -> getString(R.string.error_ip_address_not_found)
                is FatalError.BitmapFormatException -> getString(R.string.error_wrong_image_format)
                else -> appError.toString()
            }
            binding.tvFragmentStreamError.visibility = View.VISIBLE
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
            HttpClientViewHolder(ItemClientBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemId(position: Int): Long = getItem(position).id

        override fun onBindViewHolder(holder: HttpClientViewHolder, position: Int) = holder.bind(getItem(position))
    }

    private class HttpClientViewHolder(private val binding: ItemClientBinding) : RecyclerView.ViewHolder(binding.root) {

        private val textColorPrimary by lazy { ContextCompat.getColor(binding.root.context, R.color.textColorPrimary) }
        private val colorError by lazy { ContextCompat.getColor(binding.root.context, R.color.colorError) }
        private val colorAccent by lazy { ContextCompat.getColor(binding.root.context, R.color.colorAccent) }

        fun bind(product: HttpClient) = with(product) {
            binding.tvClientItemAddress.text = clientAddress
            with(binding.tvClientItemStatus) {
                when {
                    isBlocked -> {
                        setText(R.string.stream_fragment_client_blocked)
                        setTextColor(colorError)
                    }
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