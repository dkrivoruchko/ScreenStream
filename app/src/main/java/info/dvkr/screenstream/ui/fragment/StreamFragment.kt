package info.dvkr.screenstream.ui.fragment

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
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
import info.dvkr.screenstream.BaseApp
import info.dvkr.screenstream.R
import info.dvkr.screenstream.common.AppError
import info.dvkr.screenstream.common.Client
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.listenForChange
import info.dvkr.screenstream.common.settings.AppSettings
import info.dvkr.screenstream.databinding.FragmentStreamBinding
import info.dvkr.screenstream.databinding.ItemClientBinding
import info.dvkr.screenstream.databinding.ItemMjpegAddressBinding
import info.dvkr.screenstream.mjpeg.MjpegClient
import info.dvkr.screenstream.mjpeg.MjpegTrafficPoint
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import info.dvkr.screenstream.service.ServiceMessage
import info.dvkr.screenstream.service.helper.IntentAction
import info.dvkr.screenstream.ui.activity.ServiceActivity
import info.dvkr.screenstream.ui.viewBinding
import info.dvkr.screenstream.webrtc.WebRtcEnvironment
import info.dvkr.screenstream.webrtc.WebRtcPublicClient
import info.dvkr.screenstream.webrtc.WebRtcSettings
import io.nayuki.qrcodegen.QrCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.net.Inet6Address
import java.net.InetAddress

class StreamFragment : AdFragment(R.layout.fragment_stream) {

    private val colorAccent by lazy(LazyThreadSafetyMode.NONE) { ContextCompat.getColor(requireContext(), R.color.colorAccent) }
    private val clipboard: ClipboardManager? by lazy(LazyThreadSafetyMode.NONE) {
        ContextCompat.getSystemService(requireContext(), ClipboardManager::class.java)
    }

    private val appSettings: AppSettings by inject()
    private val mjpegSettings: MjpegSettings by inject()
    private val webrtcSettings: WebRtcSettings by inject()
    private val webRTCEnvironment: WebRtcEnvironment by inject()
    private var httpClientAdapter: HttpClientAdapter? = null
    private var errorPrevious: AppError? = null

    private val binding by viewBinding { fragment -> FragmentStreamBinding.bind(fragment.requireView()) }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        XLog.i(getLog("requestPermissionLauncher", "registerForActivityResult: $isGranted"))

        viewLifecycleOwner.lifecycleScope.launchWhenCreated {
            webrtcSettings.setEnableMic(isGranted)
            if (isGranted) return@launchWhenCreated
            // This is first time we get Deny
            if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) return@launchWhenCreated
            XLog.i(this@StreamFragment.getLog("requestPermissionLauncher", "shouldShowRequestPermissionRationale: false"))

            if (webrtcSettings.micPermissionDeniedFlow.first().not()) {
                XLog.i(this@StreamFragment.getLog("requestPermissionLauncher", "micPermissionDenied: false"))
                webrtcSettings.setMicPermissionDenied(true)
            } else {
                XLog.i(this@StreamFragment.getLog("requestPermissionLauncher", "Show permissions settings dialog"))
                MaterialDialog(requireContext()).show {
                    lifecycleOwner(viewLifecycleOwner)
                    icon(R.drawable.ic_permission_dialog_24dp)
                    title(R.string.stream_fragment_mode_global_audio_permission_title)
                    message(R.string.stream_fragment_mode_global_audio_permission_message_settings)
                    positiveButton(R.string.permission_activity_notification_settings) {
                        try {
                            val i = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                addCategory(Intent.CATEGORY_DEFAULT)
                                data = Uri.parse("package:${requireContext().packageName}")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                            }
                            startActivity(i)
                        } catch (ignore: ActivityNotFoundException) {
                        }
                    }
                    cancelable(false)
                    cancelOnTouchOutside(false)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadAdOnViewCreated(binding.flFragmentStreamAdViewContainer)

        binding.clFragmentStreamWebrtcMode.isVisible = false
        binding.llFragmentStreamAddresses.isVisible = false
        binding.llFragmentStreamAddresses.removeAllViews()
        binding.tvFragmentStreamPin.isVisible = false
        binding.llFragmentStreamTraffic.isVisible = false
        binding.llFragmentStreamClients.isVisible = true

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

        if ((requireActivity().applicationContext as BaseApp).isAdEnabled) {
            binding.rbFragmentStreamWebrtcMode.setOnClickListener {
                viewLifecycleOwner.lifecycleScope.launch { appSettings.setStreamMode(AppSettings.Values.STREAM_MODE_WEBRTC) }
            }
        } else {
            binding.rbFragmentStreamWebrtcMode.isEnabled = false
        }

        binding.bFragmentStreamWebrtcModeDetails.setOnClickListener {
            MaterialDialog(requireContext()).show {
                lifecycleOwner(viewLifecycleOwner)
                icon(R.drawable.ic_permission_dialog_24dp)
                title(R.string.stream_fragment_mode_global)
                message(R.string.stream_fragment_mode_global_details)
                positiveButton(android.R.string.ok)
            }
        }

        binding.rbFragmentStreamMjpegMode.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch { appSettings.setStreamMode(AppSettings.Values.STREAM_MODE_MJPEG) }
        }

        binding.bFragmentStreamMjpegModeDetails.setOnClickListener {
            MaterialDialog(requireContext()).show {
                lifecycleOwner(viewLifecycleOwner)
                icon(R.drawable.ic_permission_dialog_24dp)
                title(R.string.stream_fragment_mode_local)
                message(R.string.stream_fragment_mode_local_details)
                positiveButton(android.R.string.ok)
            }
        }

        binding.bFragmentStreamError.setOnClickListener {
            IntentAction.RecoverError.sendToAppService(requireContext())
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

        appSettings.streamModeFlow.listenForChange(viewLifecycleOwner.lifecycleScope, 0) { mode ->
            binding.rbFragmentStreamWebrtcMode.isChecked = mode == AppSettings.Values.STREAM_MODE_WEBRTC
            binding.rbFragmentStreamMjpegMode.isChecked = mode == AppSettings.Values.STREAM_MODE_MJPEG
            binding.clFragmentStreamWebrtcMode.isVisible = mode == AppSettings.Values.STREAM_MODE_WEBRTC
            binding.llFragmentStreamAddresses.isVisible = mode == AppSettings.Values.STREAM_MODE_MJPEG
            binding.llFragmentStreamAddresses.removeAllViews()
            binding.tvFragmentStreamPin.isVisible = mode == AppSettings.Values.STREAM_MODE_MJPEG
            binding.llFragmentStreamTraffic.isVisible = mode == AppSettings.Values.STREAM_MODE_MJPEG
            binding.llFragmentStreamClients.isVisible = true
        }

        webrtcSettings.enableMicFlow.listenForChange(viewLifecycleOwner.lifecycleScope, 0) { enable ->
            if (enable) {
                binding.bWebrtcStreamMic.text = getString(R.string.stream_fragment_mode_global_mic_on)
                binding.bWebrtcStreamMic.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_mic_on_24dp)
                binding.bWebrtcStreamMic.iconTint = ContextCompat.getColorStateList(requireContext(), R.color.colorError)
            } else {
                binding.bWebrtcStreamMic.text = getString(R.string.stream_fragment_mode_global_mic_off)
                binding.bWebrtcStreamMic.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_mic_off_24dp)
                binding.bWebrtcStreamMic.iconTint = null
            }
        }

        binding.bWebrtcStreamMic.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launchWhenCreated {
                val enableMic = webrtcSettings.enableMicFlow.first()

                if (enableMic) {
                    webrtcSettings.setEnableMic(false)
                    return@launchWhenCreated
                }

                val permission = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                when {
                    permission == PackageManager.PERMISSION_GRANTED -> webrtcSettings.setEnableMic(true)
                    shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                        XLog.i(this@StreamFragment.getLog("onViewCreated", "shouldShowRequestPermissionRationale: true"))
                        MaterialDialog(requireContext()).show {
                            lifecycleOwner(viewLifecycleOwner)
                            icon(R.drawable.ic_permission_dialog_24dp)
                            title(R.string.stream_fragment_mode_global_audio_permission_title)
                            message(R.string.stream_fragment_mode_global_audio_permission_message)
                            positiveButton(android.R.string.ok) {
                                XLog.i(this@StreamFragment.getLog("onViewCreated", "launch requestPermissionLauncher"))
                                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                            cancelable(false)
                            cancelOnTouchOutside(false)
                        }
                    }

                    else -> {
                        XLog.i(this@StreamFragment.getLog("onViewCreated", "launch requestPermissionLauncher"))
                        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
        }
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

    private fun InetAddress.asString(): String = if (this is Inet6Address) "[${this.hostAddress}]" else this.hostAddress ?: ""

    @SuppressLint("ClickableViewAccessibility")
    private suspend fun onServiceStateMessage(serviceMessage: ServiceMessage.ServiceState) {
        when (serviceMessage) {
            is ServiceMessage.ServiceState.MjpegServiceState -> {
                binding.llFragmentStreamAddresses.removeAllViews()

                if (serviceMessage.netInterfaces.isEmpty()) {
                    with(ItemMjpegAddressBinding.inflate(layoutInflater, binding.llFragmentStreamAddresses, false)) {
                        tvItemDeviceAddressName.text = ""
                        tvItemDeviceAddress.setText(R.string.stream_fragment_no_address)
                        tvItemDeviceAddress.setTextColor(ContextCompat.getColor(requireContext(), R.color.textColorPrimary))
                        binding.llFragmentStreamAddresses.addView(this.root)
                    }
                } else {
                    serviceMessage.netInterfaces.sortedBy { it.address.asString() }.forEach { netInterface ->
                        with(ItemMjpegAddressBinding.inflate(layoutInflater, binding.llFragmentStreamAddresses, false)) {
                            tvItemDeviceAddressName.text = getString(R.string.stream_fragment_interface, netInterface.name)
                            val fullAddress = "http://${netInterface.address.asString()}:${mjpegSettings.serverPortFlow.first()}"
                            tvItemDeviceAddress.text = fullAddress.setUnderlineSpan()
                            tvItemDeviceAddress.setOnClickListener { openInBrowser(fullAddress) }
                            ivItemDeviceAddressOpenExternal.setOnClickListener { openInBrowser(fullAddress) }
                            ivItemDeviceAddressCopy.setOnClickListener {
                                clipboard?.setPrimaryClip(ClipData.newPlainText(tvItemDeviceAddress.text, tvItemDeviceAddress.text))
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

            is ServiceMessage.ServiceState.WebRTCServiceState -> {
                binding.tvWebrtcStreamId.isVisible = serviceMessage.streamId.isNotEmpty()
                binding.tvWebrtcStreamPassword.isVisible = serviceMessage.streamId.isNotEmpty()
                binding.ivWebrtcStreamIdGetNew.isVisible = serviceMessage.streamId.isNotEmpty() && serviceMessage.isStreaming.not()
                binding.ivWebrtcStreamPasswordMakeNew.isVisible = serviceMessage.streamId.isNotEmpty() && serviceMessage.isStreaming.not()
                binding.ivWebrtcStreamPasswordShow.isVisible = serviceMessage.streamId.isNotEmpty() && serviceMessage.isStreaming
                binding.bWebrtcStreamMic.isVisible = serviceMessage.streamId.isNotEmpty()
                binding.ivWebrtcStreamAddressOpenExternal.isVisible = serviceMessage.streamId.isNotEmpty()
                binding.ivWebrtcStreamAddressCopy.isVisible = serviceMessage.streamId.isNotEmpty()
                binding.ivWebrtcStreamAddressShare.isVisible = serviceMessage.streamId.isNotEmpty()
                binding.ivWebrtcStreamAddressQr.isVisible = serviceMessage.streamId.isNotEmpty()

                if (serviceMessage.streamId.isEmpty()) {
                    binding.tvWebrtcAddress.text = getString(R.string.stream_fragment_mode_global_stream_id_getting)
                    binding.ivWebrtcStreamIdGetNew.setOnClickListener(null)
                    binding.ivWebrtcStreamPasswordMakeNew.setOnClickListener(null)
                    binding.ivWebrtcStreamPasswordShow.setOnClickListener(null)
                    binding.ivWebrtcStreamAddressOpenExternal.setOnClickListener(null)
                    binding.ivWebrtcStreamAddressCopy.setOnClickListener(null)
                    binding.ivWebrtcStreamAddressShare.setOnClickListener(null)
                    binding.ivWebrtcStreamAddressQr.setOnClickListener(null)
                } else {
                    binding.tvWebrtcAddress.text =
                        stringSpan(R.string.stream_fragment_mode_global_server_address, webRTCEnvironment.signalingServerUrl)

                    binding.tvWebrtcStreamId.text =
                        stringSpan(R.string.stream_fragment_mode_global_stream_id, serviceMessage.streamId, true)

                    binding.tvWebrtcStreamPassword.text = stringSpan(
                        R.string.stream_fragment_mode_global_stream_password,
                        if (serviceMessage.isStreaming) "*" else serviceMessage.streamPassword,
                        true
                    )

                    binding.ivWebrtcStreamIdGetNew.setOnClickListener {
                        IntentAction.GetNewStreamId.sendToAppService(requireContext()) //maybe notify user that this will disconnect all clients
                    }

                    binding.ivWebrtcStreamPasswordMakeNew.setOnClickListener {
                        IntentAction.CreateNewStreamPassword.sendToAppService(requireContext())  //maybe notify user that this will disconnect all clients
                    }

                    binding.ivWebrtcStreamPasswordShow.setOnTouchListener { _, event ->
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> binding.tvWebrtcStreamPassword.text =
                                stringSpan(R.string.stream_fragment_mode_global_stream_password, serviceMessage.streamPassword, true)

                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> binding.tvWebrtcStreamPassword.text =
                                stringSpan(R.string.stream_fragment_mode_global_stream_password, "*", true)
                        }
                        true
                    }

                    val fullAddress =
                        webRTCEnvironment.signalingServerUrl + "/?id=${serviceMessage.streamId}&p=${serviceMessage.streamPassword}"

                    binding.ivWebrtcStreamAddressOpenExternal.setOnClickListener { openInBrowser(fullAddress) }
                    binding.ivWebrtcStreamAddressCopy.setOnClickListener {
                        clipboard?.setPrimaryClip(ClipData.newPlainText(fullAddress, fullAddress))
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2)
                            Toast.makeText(requireContext(), R.string.stream_fragment_copied, Toast.LENGTH_LONG).show()
                    }
                    binding.ivWebrtcStreamAddressShare.setOnClickListener { shareAddress(fullAddress) }
                    binding.ivWebrtcStreamAddressQr.setOnClickListener { showQrCode(fullAddress) }
                }

                showError(serviceMessage.appError)
            }
        }
    }

    private fun stringSpan(@StringRes resId: Int, suffix: String, monoFont: Boolean = false): SpannableString {
        val string = getString(resId, suffix)
        return string
            .setColorSpan(colorAccent, string.length - suffix.length)
            .setBoldSpan(string.length - suffix.length)
            .let { if (monoFont) it.setMonoFontSpan(string.length - suffix.length) else it }
    }

    private fun onClientsMessage(serviceMessage: ServiceMessage.Clients) {
        if (serviceMessage.clients.firstOrNull() is MjpegClient) {
            val clientsCount = serviceMessage.clients.count { (it as MjpegClient).isDisconnected.not() }
            binding.tvFragmentStreamClientsHeader.text = getString(R.string.stream_fragment_connected_clients).run {
                format(clientsCount).setColorSpan(colorAccent, indexOf('%'))
            }
            httpClientAdapter?.submitList(serviceMessage.clients.map { it as MjpegClient })
        } else if (serviceMessage.clients.firstOrNull() is WebRtcPublicClient) {
            val clientsCount = serviceMessage.clients.count()
            binding.tvFragmentStreamClientsHeader.text = getString(R.string.stream_fragment_connected_clients).run {
                format(clientsCount).setColorSpan(colorAccent, indexOf('%'))
            }
            httpClientAdapter?.submitList(serviceMessage.clients.map { it as WebRtcPublicClient })
        } else {
            binding.tvFragmentStreamClientsHeader.text = getString(R.string.stream_fragment_connected_clients).run {
                format(0).setColorSpan(colorAccent, indexOf('%'))
            }
            httpClientAdapter?.submitList(emptyList())
        }
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
            Toast.makeText(requireContext().applicationContext, R.string.stream_fragment_no_web_browser_found, Toast.LENGTH_LONG).show()
            XLog.w(getLog("openInBrowser", ex.toString()))
        } catch (ex: SecurityException) {
            Toast.makeText(requireContext().applicationContext, R.string.stream_fragment_external_app_error, Toast.LENGTH_LONG).show()
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
            binding.clFragmentStreamError.visibility = View.GONE
        } else {
            XLog.d(getLog("showError", appError.toString()))
            binding.tvFragmentStreamError.text = appError.toString(requireContext())
            binding.clFragmentStreamError.visibility = View.VISIBLE
        }

        errorPrevious = appError
    }

    private class HttpClientAdapter : ListAdapter<Client, HttpClientViewHolder>(
        object : DiffUtil.ItemCallback<Client>() {
            override fun areItemsTheSame(oldItem: Client, newItem: Client): Boolean = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Client, newItem: Client): Boolean = oldItem == newItem
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

        fun bind(product: Client) = when (product) {
            is MjpegClient -> with(product) {
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

            is WebRtcPublicClient -> with(product) {
                binding.tvClientItemAddress.text = clientAddress
                binding.tvClientItemStatus.setText(R.string.stream_fragment_client_connected)
                binding.tvClientItemStatus.setTextColor(colorAccent)
            }

            else -> throw IllegalArgumentException("Unexpected Client class: ${product::class.java}")
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

    private fun String.setColorSpan(color: Int, start: Int = 0, end: Int = this.length) = SpannableString(this).apply {
        setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun SpannableString.setBoldSpan(start: Int = 0, end: Int = this.length) = SpannableString(this).apply {
        setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private val typeface by lazy(LazyThreadSafetyMode.NONE) {
        Typeface.create(ResourcesCompat.getFont(requireContext(), R.font.mono), Typeface.BOLD)
    }

    private fun SpannableString.setMonoFontSpan(start: Int = 0, end: Int = this.length) = SpannableString(this).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            setSpan(TypefaceSpan(typeface), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            setSpan(TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun String.setUnderlineSpan(start: Int = 0, end: Int = this.length) = SpannableString(this).apply {
        setSpan(UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}