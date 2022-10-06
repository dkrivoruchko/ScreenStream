package info.dvkr.screenstream.ui.fragment

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
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
import info.dvkr.screenstream.common.AppError
import info.dvkr.screenstream.common.asString
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.databinding.FragmentStreamBinding
import info.dvkr.screenstream.databinding.ItemClientBinding
import info.dvkr.screenstream.databinding.ItemDeviceAddressBinding
import info.dvkr.screenstream.mjpeg.*
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import info.dvkr.screenstream.service.ServiceMessage
import info.dvkr.screenstream.service.helper.IntentAction
import info.dvkr.screenstream.ui.activity.ServiceActivity
import info.dvkr.screenstream.ui.viewBinding
import io.nayuki.qrcodegen.QrCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.koin.android.ext.android.inject

class StreamFragment : AdFragment(R.layout.fragment_stream) {

    private val colorAccent by lazy(LazyThreadSafetyMode.NONE) { ContextCompat.getColor(requireContext(), R.color.colorAccent) }
    private val clipboard: ClipboardManager? by lazy(LazyThreadSafetyMode.NONE) {
        ContextCompat.getSystemService(requireContext(), ClipboardManager::class.java)
    }

    private val mjpegSettings: MjpegSettings by inject()
    private var httpClientAdapter: HttpClientAdapter? = null
    private var errorPrevious: AppError? = null

    private val binding by viewBinding { fragment -> FragmentStreamBinding.bind(fragment.requireView()) }

    private fun String.setColorSpan(color: Int, start: Int = 0, end: Int = this.length) = SpannableString(this).apply {
        setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun String.setUnderlineSpan(start: Int = 0, end: Int = this.length) = SpannableString(this).apply {
        setSpan(UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

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

        (requireActivity() as ServiceActivity).serviceMessageFlow
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .onEach { serviceMessage ->
                when (serviceMessage) {
                    is ServiceMessage.ServiceState -> onServiceStateMessage(serviceMessage)
                    is ServiceMessage.Clients -> onClientsMessage(serviceMessage)
                    is ServiceMessage.TrafficHistory -> onTrafficHistoryMessage(serviceMessage)
                    is ServiceMessage.FinishActivity -> Unit
                }
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    override fun onStart() {
        super.onStart()
        XLog.d(getLog("onStart"))

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
                        "http://${netInterface.address.asString()}:${mjpegSettings.serverPortFlow.first()}"
                    tvItemDeviceAddress.text = fullAddress.setUnderlineSpan()
                    tvItemDeviceAddress.setOnClickListener { openInBrowser(fullAddress) }
                    ivItemDeviceAddressOpenExternal.setOnClickListener { openInBrowser(fullAddress) }
                    ivItemDeviceAddressCopy.setOnClickListener {
                        clipboard?.setPrimaryClip(
                            ClipData.newPlainText(tvItemDeviceAddress.text, tvItemDeviceAddress.text)
                        )
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2)
                            Toast.makeText(requireContext(), R.string.stream_fragment_copied, Toast.LENGTH_LONG).show()
                    }
                    ivItemDeviceAddressShare.setOnClickListener { shareAddress(fullAddress) }
                    ivItemDeviceAddressQr.setOnClickListener { showQrCode(fullAddress) }
                    binding.llFragmentStreamAddresses.addView(this.root)
                }
            }
        }

        // Hide pin on Start
        if (mjpegSettings.enablePinFlow.first()) {
            if (serviceMessage.isStreaming && mjpegSettings.hidePinOnStartFlow.first()) {
                val pinText = getString(R.string.stream_fragment_pin, "*")
                binding.tvFragmentStreamPin.text = pinText.setColorSpan(colorAccent, pinText.length - 1)
            } else {
                val pinText = getString(R.string.stream_fragment_pin, mjpegSettings.pinFlow.first())
                binding.tvFragmentStreamPin.text =
                    pinText.setColorSpan(colorAccent, pinText.length - mjpegSettings.pinFlow.first().length)
            }
        } else {
            binding.tvFragmentStreamPin.setText(R.string.stream_fragment_pin_disabled)
        }

        showError(serviceMessage.appError)
    }

    private fun onClientsMessage(serviceMessage: ServiceMessage.Clients) {
        val clientsCount = serviceMessage.clients.count { (it as MjpegClient).isDisconnected.not() }
        binding.tvFragmentStreamClientsHeader.text = getString(R.string.stream_fragment_connected_clients).run {
            format(clientsCount).setColorSpan(colorAccent, indexOf('%'))
        }
        httpClientAdapter?.submitList(serviceMessage.clients.map { it as MjpegClient })
    }

    private fun Long.bytesToMbit() = (this * 8).toFloat() / 1024 / 1024

    private fun onTrafficHistoryMessage(serviceMessage: ServiceMessage.TrafficHistory) {
        binding.tvFragmentStreamTrafficHeader.text = getString(R.string.stream_fragment_current_traffic).run {
            val lastTrafficPoint = serviceMessage.trafficHistory.lastOrNull() as? MjpegTrafficPoint ?: return@run "0"
            format(lastTrafficPoint.bytes.bytesToMbit()).setColorSpan(colorAccent, indexOf('%'))
        }

        binding.trafficGraphFragmentStream.setDataPoints(
            serviceMessage.trafficHistory.map { it as MjpegTrafficPoint }.map { Pair(it.time, it.bytes.bytesToMbit()) }
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
                is AddressInUseException -> getString(R.string.error_port_in_use)
                is CastSecurityException -> getString(R.string.error_invalid_media_projection)
                is AddressNotFoundException -> getString(R.string.error_ip_address_not_found)
                is BitmapFormatException -> getString(R.string.error_wrong_image_format)
                else -> appError.toString()
            }
            binding.tvFragmentStreamError.visibility = View.VISIBLE
        }

        errorPrevious = appError
    }

    private class HttpClientAdapter : ListAdapter<MjpegClient, HttpClientViewHolder>(
        object : DiffUtil.ItemCallback<MjpegClient>() {
            override fun areItemsTheSame(oldItem: MjpegClient, newItem: MjpegClient): Boolean = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: MjpegClient, newItem: MjpegClient): Boolean = oldItem == newItem
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

        fun bind(product: MjpegClient) = with(product) {
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

    private fun String.getQRBitmap(size: Int): Bitmap? =
        try {
            val qrCode = QrCode.encodeText(this, QrCode.Ecc.MEDIUM)
            val scale = size / qrCode.size
            val pixels = IntArray(size * size).apply { fill(0xFFFFFFFF.toInt()) }
            for (y in 0 until size)
                for (x in 0 until size)
                    if (qrCode.getModule(x / scale, y / scale)) pixels[y * size + x] = 0xFF000000.toInt()

            val border = 16
            Bitmap.createBitmap(size + border, size + border, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, size, border, border, size, size)
            }
        } catch (ex: Exception) {
            XLog.e(getLog("String.getQRBitmap", ex.toString()))
            null
        }
}