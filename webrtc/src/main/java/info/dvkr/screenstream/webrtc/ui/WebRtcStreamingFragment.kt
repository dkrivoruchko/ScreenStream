package info.dvkr.screenstream.webrtc.ui


import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RestrictTo
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.callbacks.onDismiss
import com.afollestad.materialdialogs.customview.customView
import com.afollestad.materialdialogs.lifecycle.lifecycleOwner
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.view.viewBinding
import info.dvkr.screenstream.webrtc.R
import info.dvkr.screenstream.webrtc.WebRtcKoinQualifier
import info.dvkr.screenstream.webrtc.WebRtcSettings
import info.dvkr.screenstream.webrtc.WebRtcStateFlowProvider
import info.dvkr.screenstream.webrtc.WebRtcStreamingModule
import info.dvkr.screenstream.webrtc.databinding.FragmentWebrtcStreamBinding
import info.dvkr.screenstream.webrtc.databinding.ItemWebrtcClientBinding
import info.dvkr.screenstream.webrtc.internal.ClientId
import info.dvkr.screenstream.webrtc.internal.WebRtcError
import info.dvkr.screenstream.webrtc.internal.WebRtcEvent
import info.dvkr.screenstream.webrtc.internal.WebRtcState
import io.nayuki.qrcodegen.QrCode
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.core.qualifier.named

@RestrictTo(RestrictTo.Scope.LIBRARY)
public class WebRtcStreamingFragment : Fragment(R.layout.fragment_webrtc_stream) {

    public companion object {
        private const val KEY_CAST_PERMISSION_PENDING = "KEY_CAST_PERMISSION_PENDING"
    }

    private val binding by viewBinding { fragment -> FragmentWebrtcStreamBinding.bind(fragment.requireView()) }

    private val webRtcStreamingModule: WebRtcStreamingModule by inject(named(WebRtcKoinQualifier), LazyThreadSafetyMode.NONE)
    private val webRtcSettings: WebRtcSettings by lazy(LazyThreadSafetyMode.NONE) { webRtcStreamingModule.scope.get() }

    private val colorAccent by lazy(LazyThreadSafetyMode.NONE) { ContextCompat.getColor(requireContext(), R.color.colorAccent) }
    private val clipboard: ClipboardManager? by lazy(LazyThreadSafetyMode.NONE) {
        ContextCompat.getSystemService(requireContext(), ClipboardManager::class.java)
    }

    private var webRtcClientAdapter: WebRtcClientAdapter? = null
    private var errorPrevious: WebRtcError? = null

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        XLog.i(getLog("requestPermissionLauncher", "registerForActivityResult: $isGranted"))

        viewLifecycleOwner.lifecycleScope.launch {
            webRtcSettings.setEnableMic(isGranted)
            if (isGranted) return@launch
            // This is first time we get Deny
            if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) return@launch
            XLog.i(this@WebRtcStreamingFragment.getLog("requestPermissionLauncher", "shouldShowRequestPermissionRationale: false"))

            if (webRtcSettings.micPermissionDeniedFlow.first().not()) {
                XLog.i(this@WebRtcStreamingFragment.getLog("requestPermissionLauncher", "micPermissionDenied: false"))
                webRtcSettings.setMicPermissionDenied(true)
            } else {
                XLog.i(this@WebRtcStreamingFragment.getLog("requestPermissionLauncher", "Show permissions settings dialog"))
                MaterialDialog(requireContext()).show {
                    lifecycleOwner(viewLifecycleOwner)
                    icon(R.drawable.webrtc_ic_permission_dialog_24dp)
                    title(R.string.webrtc_stream_fragment_audio_permission_title)
                    message(R.string.webrtc_stream_fragment_audio_permission_message_settings)
                    positiveButton(R.string.webrtc_stream_fragment_audio_permission_open_settings) {
                        try {
                            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
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
                }
            }
        }
    }

    private var permissionsErrorDialog: MaterialDialog? = null
    private var castPermissionsPending: Boolean = false
    private val startMediaProjection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == AppCompatActivity.RESULT_OK) {
            XLog.d(getLog("registerForActivityResult", "Cast permission granted"))
            webRtcStreamingModule.sendEvent(WebRtcEvent.StartProjection(result.data!!))
        } else {
            XLog.w(getLog("registerForActivityResult", "Cast permission denied"))
            webRtcStreamingModule.sendEvent(WebRtcEvent.CastPermissionsDenied)
            permissionsErrorDialog?.dismiss()
            showErrorDialog(R.string.webrtc_stream_fragment_cast_permission_required_title, R.string.webrtc_stream_fragment_cast_permission_required)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        XLog.d(getLog("onViewCreated"))
        castPermissionsPending = savedInstanceState?.getBoolean(KEY_CAST_PERMISSION_PENDING) ?: false

        binding.bFragmentStreamSettings.setOnClickListener {
            if (childFragmentManager.findFragmentByTag(WebRtcSettingsFragment.TAG) == null)
                WebRtcSettingsFragment().showNow(childFragmentManager, WebRtcSettingsFragment.TAG)
        }

        with(binding.rvFragmentStreamClients) {
            itemAnimator = DefaultItemAnimator()
            webRtcClientAdapter = WebRtcClientAdapter { clientId ->
                webRtcStreamingModule.sendEvent(WebRtcEvent.RemoveClient(clientId, true, "User request"))
            }.apply { setHasStableIds(true) }
            adapter = webRtcClientAdapter
        }

        binding.bFragmentStreamError.setOnClickListener {
            webRtcStreamingModule.sendEvent(WebRtcEvent.Intentable.RecoverError)
        }

        webRtcStreamingModule.scope.get<WebRtcStateFlowProvider>().mutableWebRtcStateFlow.asStateFlow()
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .onEach { state -> onWebRtcState(state) }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        webRtcSettings.enableMicFlow.distinctUntilChanged().onEach { enable ->
            if (enable) {
                binding.bWebrtcStreamMic.text = getString(R.string.webrtc_stream_fragment_mic_on)
                binding.bWebrtcStreamMic.icon = ContextCompat.getDrawable(requireContext(), R.drawable.webrtc_ic_mic_on_24dp)
                binding.bWebrtcStreamMic.iconTint = ContextCompat.getColorStateList(requireContext(), R.color.colorError)
            } else {
                binding.bWebrtcStreamMic.text = getString(R.string.webrtc_stream_fragment_mic_off)
                binding.bWebrtcStreamMic.icon = ContextCompat.getDrawable(requireContext(), R.drawable.webrtc_ic_mic_off_24dp)
                binding.bWebrtcStreamMic.iconTint = null
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        binding.bWebrtcStreamMic.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                if (webRtcSettings.enableMicFlow.first()) {
                    webRtcSettings.setEnableMic(false)
                    return@launch
                }

                val permission = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                when {
                    permission == PackageManager.PERMISSION_GRANTED -> webRtcSettings.setEnableMic(true)
                    shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                        XLog.i(this@WebRtcStreamingFragment.getLog("onViewCreated", "shouldShowRequestPermissionRationale: true"))
                        MaterialDialog(requireContext()).show {
                            lifecycleOwner(viewLifecycleOwner)
                            icon(R.drawable.webrtc_ic_permission_dialog_24dp)
                            title(R.string.webrtc_stream_fragment_audio_permission_title)
                            message(R.string.webrtc_stream_fragment_audio_permission_message)
                            positiveButton(android.R.string.ok) {
                                XLog.i(this@WebRtcStreamingFragment.getLog("onViewCreated", "launch requestPermissionLauncher"))
                                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    }

                    else -> {
                        XLog.i(this@WebRtcStreamingFragment.getLog("onViewCreated", "launch requestPermissionLauncher"))
                        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_CAST_PERMISSION_PENDING, castPermissionsPending)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        XLog.d(getLog("onDestroyView"))
        super.onDestroyView()
        webRtcClientAdapter = null
    }

    private fun requestCastPermission() {
        if (castPermissionsPending) {
            XLog.i(getLog("requestCastPermission", "Ignoring: castPermissionsPending == true"))
            return
        }
        permissionsErrorDialog?.dismiss()
        castPermissionsPending = true
        try {
            val projectionManager = ContextCompat.getSystemService(requireContext(), MediaProjectionManager::class.java)!!
            val intent = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                projectionManager.createScreenCaptureIntent()
            } else {
                projectionManager.createScreenCaptureIntent()
                //TODO   MediaProjectionConfig.createConfigForDefaultDisplay()
                //TODO   MediaProjectionConfig.createConfigForUserChoice()
            }

            startMediaProjection.launch(intent)
        } catch (ignore: ActivityNotFoundException) {
            XLog.w(getLog("requestCastPermission", "ActivityNotFoundException"), ignore) //TODO Expected for Android 5 only
            showErrorDialog(
                R.string.webrtc_stream_fragment_cast_permission_activity_not_found_title,
                R.string.webrtc_stream_fragment_cast_permission_activity_not_found
            )
        }
    }

    private fun showErrorDialog(@StringRes titleRes: Int, @StringRes messageRes: Int) {
        permissionsErrorDialog = MaterialDialog(requireActivity()).show {
            lifecycleOwner(viewLifecycleOwner)
            icon(R.drawable.webrtc_ic_permission_dialog_24dp)
            title(titleRes)
            message(messageRes)
            positiveButton(android.R.string.ok)
            cancelable(false)
            cancelOnTouchOutside(false)
            onDismiss { permissionsErrorDialog = null }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun onWebRtcState(state: WebRtcState) {
        if (state.waitingCastPermission) requestCastPermission() else castPermissionsPending = false

        binding.tvWebrtcStreamId.isVisible = state.streamId.isNotEmpty()
        binding.tvWebrtcStreamPassword.isVisible = state.streamId.isNotEmpty()
        binding.ivWebrtcStreamIdGetNew.isVisible = state.streamId.isNotEmpty() && state.isStreaming.not()
        binding.ivWebrtcStreamPasswordMakeNew.isVisible = state.streamId.isNotEmpty() && state.isStreaming.not()
        binding.ivWebrtcStreamPasswordShow.isVisible = state.streamId.isNotEmpty() && state.isStreaming
        binding.bWebrtcStreamMic.isVisible = state.streamId.isNotEmpty()
        binding.ivWebrtcStreamAddressOpenExternal.isVisible = state.streamId.isNotEmpty()
        binding.ivWebrtcStreamAddressCopy.isVisible = state.streamId.isNotEmpty()
        binding.ivWebrtcStreamAddressShare.isVisible = state.streamId.isNotEmpty()
        binding.ivWebrtcStreamAddressQr.isVisible = state.streamId.isNotEmpty()

        if (state.streamId.isEmpty()) {
            binding.tvWebrtcAddress.text = getString(R.string.webrtc_stream_fragment_stream_id_getting)
            binding.ivWebrtcStreamIdGetNew.setOnClickListener(null)
            binding.ivWebrtcStreamPasswordMakeNew.setOnClickListener(null)
            binding.ivWebrtcStreamPasswordShow.setOnClickListener(null)
            binding.ivWebrtcStreamAddressOpenExternal.setOnClickListener(null)
            binding.ivWebrtcStreamAddressCopy.setOnClickListener(null)
            binding.ivWebrtcStreamAddressShare.setOnClickListener(null)
            binding.ivWebrtcStreamAddressQr.setOnClickListener(null)
        } else {
            binding.tvWebrtcAddress.text =
                stringSpan(R.string.webrtc_stream_fragment_server_address, state.signalingServerUrl)

            binding.tvWebrtcStreamId.text =
                stringSpan(R.string.webrtc_stream_fragment_stream_id, state.streamId, true)

            binding.tvWebrtcStreamPassword.text = stringSpan(
                R.string.webrtc_stream_fragment_stream_password,
                if (state.isStreaming) "*" else state.streamPassword,
                true
            )

            binding.ivWebrtcStreamIdGetNew.setOnClickListener {//TODO notify user that this will disconnect all clients
                webRtcStreamingModule.sendEvent(WebRtcEvent.GetNewStreamId)
            }

            binding.ivWebrtcStreamPasswordMakeNew.setOnClickListener {//TODO notify user that this will disconnect all clients
                webRtcStreamingModule.sendEvent(WebRtcEvent.CreateNewPassword)
            }

            binding.ivWebrtcStreamPasswordShow.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> binding.tvWebrtcStreamPassword.text =
                        stringSpan(R.string.webrtc_stream_fragment_stream_password, state.streamPassword, true)

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> binding.tvWebrtcStreamPassword.text =
                        stringSpan(R.string.webrtc_stream_fragment_stream_password, "*", true)
                }
                true
            }

            val fullAddress = state.signalingServerUrl + "/?id=${state.streamId}&p=${state.streamPassword}"

            binding.ivWebrtcStreamAddressOpenExternal.setOnClickListener { openInBrowser(fullAddress) }
            binding.ivWebrtcStreamAddressCopy.setOnClickListener {
                clipboard?.setPrimaryClip(ClipData.newPlainText(fullAddress, fullAddress))
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2)
                    Toast.makeText(requireContext(), R.string.webrtc_stream_fragment_copied, Toast.LENGTH_LONG).show()
            }
            binding.ivWebrtcStreamAddressShare.setOnClickListener { shareAddress(fullAddress) }
            binding.ivWebrtcStreamAddressQr.setOnClickListener { showQrCode(fullAddress) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            binding.bWebrtcStreamMic.isEnabled = state.isStreaming.not() //TODO Icon is not dimmed
        }

        val clientsCount = state.clients.count()
        binding.tvFragmentStreamClientsHeader.text = getString(R.string.webrtc_stream_fragment_connected_clients).run {
            format(clientsCount).setColorSpan(colorAccent, indexOf('%'))
        }
        webRtcClientAdapter?.submitList(state.clients)


        if (errorPrevious != state.error) {
            if (state.error == null) {
                binding.clFragmentStreamError.visibility = View.GONE
            } else {
                XLog.d(getLog("showError", state.error.toString()))
                binding.tvFragmentStreamError.text = state.error.toString(requireContext())
                binding.clFragmentStreamError.visibility = View.VISIBLE
            }

            errorPrevious = state.error
        }
    }

    private class WebRtcClientAdapter(private val onDisconnect: (ClientId) -> Unit) :
        ListAdapter<WebRtcState.Client, WebRtcClientViewHolder>(
            object : DiffUtil.ItemCallback<WebRtcState.Client>() {
                override fun areItemsTheSame(oldItem: WebRtcState.Client, newItem: WebRtcState.Client): Boolean = oldItem.id == newItem.id
                override fun areContentsTheSame(oldItem: WebRtcState.Client, newItem: WebRtcState.Client): Boolean = oldItem == newItem
            }
        ) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            WebRtcClientViewHolder(ItemWebrtcClientBinding.inflate(LayoutInflater.from(parent.context), parent, false), onDisconnect)

        override fun getItemId(position: Int): Long = getItem(position).id.hashCode().toLong()

        override fun onBindViewHolder(holder: WebRtcClientViewHolder, position: Int) = holder.bind(getItem(position))
    }

    private class WebRtcClientViewHolder(private val binding: ItemWebrtcClientBinding, private val onDisconnect: (ClientId) -> Unit) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(product: WebRtcState.Client) = with(product) {
            binding.tvClientItemId.text = "${publicId.substring(0, 4)}-${publicId.substring(4)}"
            binding.tvClientItemAddress.text = clientAddress
            binding.tvClientItemAddress.gravity = Gravity.CENTER_HORIZONTAL
            binding.bClientItemDisconnect.setOnClickListener { onDisconnect.invoke(ClientId(product.id)) }
        }
    }

    private fun openInBrowser(fullAddress: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fullAddress)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (ex: ActivityNotFoundException) {
            Toast.makeText(requireContext().applicationContext, R.string.webrtc_stream_fragment_no_web_browser_found, Toast.LENGTH_LONG).show()
            XLog.w(getLog("openInBrowser", ex.toString()))
        } catch (ex: SecurityException) {
            Toast.makeText(requireContext().applicationContext, R.string.webrtc_stream_fragment_external_app_error, Toast.LENGTH_LONG).show()
            XLog.w(getLog("openInBrowser", ex.toString()))
        }
    }

    private fun shareAddress(fullAddress: String) {
        val sharingIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, fullAddress)
        }
        startActivity(Intent.createChooser(sharingIntent, getString(R.string.webrtc_stream_fragment_share_address)))
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

    private fun stringSpan(@StringRes resId: Int, suffix: String, monoFont: Boolean = false): SpannableString {
        val string = getString(resId, suffix)
        return string
            .setColorSpan(colorAccent, string.length - suffix.length)
            .setBoldSpan(string.length - suffix.length)
            .let { if (monoFont) it.setMonoFontSpan(string.length - suffix.length) else it }
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

}