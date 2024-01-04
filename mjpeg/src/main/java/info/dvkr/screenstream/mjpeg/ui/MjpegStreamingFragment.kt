package info.dvkr.screenstream.mjpeg.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
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
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.view.viewBinding
import info.dvkr.screenstream.mjpeg.MjpegKoinQualifier
import info.dvkr.screenstream.mjpeg.MjpegStreamingModule
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.databinding.FragmentMjpegStreamBinding
import info.dvkr.screenstream.mjpeg.databinding.ItemMjpegAddressBinding
import info.dvkr.screenstream.mjpeg.databinding.ItemMjpegClientBinding
import info.dvkr.screenstream.mjpeg.internal.MjpegError
import info.dvkr.screenstream.mjpeg.internal.MjpegEvent
import info.dvkr.screenstream.mjpeg.internal.MjpegState
import io.nayuki.qrcodegen.QrCode
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import org.koin.android.ext.android.inject
import org.koin.core.qualifier.named
import java.net.Inet6Address
import java.net.InetAddress

@RestrictTo(RestrictTo.Scope.LIBRARY)
public class MjpegStreamingFragment : Fragment(R.layout.fragment_mjpeg_stream) {

    private companion object {
        private const val KEY_CAST_PERMISSION_PENDING = "KEY_CAST_PERMISSION_PENDING"
    }

    private val binding by viewBinding { fragment -> FragmentMjpegStreamBinding.bind(fragment.requireView()) }

    private val mjpegStreamingModule: MjpegStreamingModule by inject(named(MjpegKoinQualifier), LazyThreadSafetyMode.NONE)

    private val colorAccent by lazy(LazyThreadSafetyMode.NONE) { ContextCompat.getColor(requireContext(), R.color.colorAccent) }
    private val clipboard: ClipboardManager? by lazy(LazyThreadSafetyMode.NONE) {
        ContextCompat.getSystemService(requireContext(), ClipboardManager::class.java)
    }

    private var httpClientAdapter: HttpClientAdapter? = null
    private var errorPrevious: MjpegError? = null

    private var permissionsErrorDialog: MaterialDialog? = null
    private var castPermissionsPending: Boolean = false
    private val startMediaProjection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == AppCompatActivity.RESULT_OK) {
            XLog.d(getLog("registerForActivityResult", "Cast permission granted"))
            mjpegStreamingModule.sendEvent(MjpegEvent.StartProjection(result.data!!))
        } else {
            XLog.w(getLog("registerForActivityResult", "Cast permission denied"))
            mjpegStreamingModule.sendEvent(MjpegEvent.CastPermissionsDenied)
            permissionsErrorDialog?.dismiss()
            showErrorDialog(R.string.mjpeg_stream_fragment_cast_permission_required_title, R.string.mjpeg_stream_fragment_cast_permission_required)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        XLog.d(getLog("onViewCreated"))
        super.onViewCreated(view, savedInstanceState)
        castPermissionsPending = savedInstanceState?.getBoolean(KEY_CAST_PERMISSION_PENDING) ?: false

        binding.bFragmentStreamSettings.setOnClickListener {
            if (childFragmentManager.findFragmentByTag(MjpegSettingsFragment.TAG) == null)
                MjpegSettingsFragment().showNow(childFragmentManager, MjpegSettingsFragment.TAG)
        }

        with(binding.rvFragmentStreamClients) {
            itemAnimator = DefaultItemAnimator()
            httpClientAdapter = HttpClientAdapter().apply { setHasStableIds(true) }
            adapter = httpClientAdapter
        }

        binding.bFragmentStreamError.setOnClickListener {
            mjpegStreamingModule.sendEvent(MjpegEvent.Intentable.RecoverError)
        }

        mjpegStreamingModule.mjpegStateFlow.combineTransform(mjpegStreamingModule.isRunning) { mjpegState, isRunning ->
            if (mjpegState != null && isRunning) emit(mjpegState)
        }
            .onStart { XLog.i(this@MjpegStreamingFragment.getLog("mjpegStreamingModule.mjpegStateFlow.onStart")) }
            .onEach { state -> onMjpegState(state) }
            .onCompletion { XLog.i(this@MjpegStreamingFragment.getLog("mjpegStreamingModule.mjpegStateFlow.onCompletion")) }
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_CAST_PERMISSION_PENDING, castPermissionsPending)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        XLog.d(getLog("onDestroyView"))
        (childFragmentManager.findFragmentByTag(MjpegSettingsFragment.TAG) as? BottomSheetDialogFragment)?.dismissAllowingStateLoss()
        super.onDestroyView()
        httpClientAdapter = null
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
        } catch (cause: Throwable) {
            XLog.w(getLog("requestCastPermission", "requestCastPermission: $cause"), cause)
            showErrorDialog(
                R.string.mjpeg_stream_fragment_cast_permission_error_title,
                R.string.mjpeg_stream_fragment_cast_permission_error
            )
        }
    }

    private fun showErrorDialog(@StringRes titleRes: Int, @StringRes messageRes: Int) {
        permissionsErrorDialog = MaterialDialog(requireActivity()).show {
            lifecycleOwner(viewLifecycleOwner)
            icon(R.drawable.mjpeg_ic_permission_dialog_24dp)
            title(titleRes)
            message(messageRes)
            positiveButton(android.R.string.ok)
            cancelable(false)
            cancelOnTouchOutside(false)
            onDismiss { permissionsErrorDialog = null }
        }
    }

    private fun InetAddress.asString(): String = if (this is Inet6Address) "[${this.hostAddress}]" else this.hostAddress ?: ""

    @SuppressLint("ClickableViewAccessibility")
    private suspend fun onMjpegState(state: MjpegState) {
        XLog.d(getLog("onMjpegState", state.toString()))

        val mjpegSettings = mjpegStreamingModule.mjpegSettings

        if (state.waitingCastPermission) requestCastPermission() else castPermissionsPending = false

        binding.llFragmentStreamAddresses.removeAllViews()

        if (state.netInterfaces.isEmpty()) {
            with(ItemMjpegAddressBinding.inflate(LayoutInflater.from(requireContext()), binding.llFragmentStreamAddresses, false)) {
                tvItemDeviceAddressName.text = ""
                tvItemDeviceAddress.setText(R.string.mjpeg_stream_fragment_no_address)
                tvItemDeviceAddress.setTextColor(ContextCompat.getColor(requireContext(), R.color.textColorPrimary))
                binding.llFragmentStreamAddresses.addView(this.root)
            }
        } else {
            state.netInterfaces.sortedBy { it.address.asString() }.forEach { netInterface ->
                with(ItemMjpegAddressBinding.inflate(LayoutInflater.from(requireContext()), binding.llFragmentStreamAddresses, false)) {
                    tvItemDeviceAddressName.text = getString(R.string.mjpeg_stream_fragment_interface, netInterface.name)
                    val fullAddress = "http://${netInterface.address.asString()}:${mjpegSettings.serverPortFlow.first()}"
                    tvItemDeviceAddress.text = fullAddress.setUnderlineSpan()
                    tvItemDeviceAddress.setOnClickListener { openInBrowser(fullAddress) }
                    ivItemDeviceAddressOpenExternal.setOnClickListener { openInBrowser(fullAddress) }
                    ivItemDeviceAddressCopy.setOnClickListener {
                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(tvItemDeviceAddress.text, tvItemDeviceAddress.text))
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2)
                            Toast.makeText(requireContext(), R.string.mjpeg_stream_fragment_copied, Toast.LENGTH_LONG).show()
                    }
                    ivItemDeviceAddressShare.setOnClickListener { shareAddress(fullAddress) }
                    ivItemDeviceAddressQr.setOnClickListener { showQrCode(fullAddress) }
                    binding.llFragmentStreamAddresses.addView(this.root)
                }
            }
        }


        val enablePin = mjpegSettings.enablePinFlow.first()
        binding.ivFragmentStreamPinShow.isVisible = enablePin && state.isStreaming
        binding.ivFragmentStreamPinMakeNew.isVisible = enablePin && state.isStreaming.not()

        val pin = mjpegSettings.pinFlow.first()
        binding.ivFragmentStreamPinShow.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val pinText = getString(R.string.mjpeg_stream_fragment_pin, pin)
                    binding.tvFragmentStreamPin.text = pinText.setColorSpan(colorAccent, pinText.length - pin.length)
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val pinText = getString(R.string.mjpeg_stream_fragment_pin, "*")
                    binding.tvFragmentStreamPin.text = pinText.setColorSpan(colorAccent, pinText.length - 1)
                }
            }
            true
        }

        // Hide pin on Start
        if (enablePin) {
            if (state.isStreaming && mjpegSettings.hidePinOnStartFlow.first()) {
                val pinText = getString(R.string.mjpeg_stream_fragment_pin, "*")
                binding.tvFragmentStreamPin.text = pinText.setColorSpan(colorAccent, pinText.length - 1)
            } else {
                val pinText = getString(R.string.mjpeg_stream_fragment_pin, pin)
                binding.tvFragmentStreamPin.text = pinText.setColorSpan(colorAccent, pinText.length - pin.length)
            }
            binding.ivFragmentStreamPinMakeNew.setOnClickListener {//TODO notify user that this will disconnect all clients
                mjpegStreamingModule.sendEvent(MjpegEvent.CreateNewPin)
            }
        } else {
            binding.tvFragmentStreamPin.setText(R.string.mjpeg_stream_fragment_pin_disabled)
            binding.ivFragmentStreamPinMakeNew.setOnClickListener(null)
        }

        val clientsCount = state.clients.count { it.state != MjpegState.Client.State.DISCONNECTED }
        binding.tvFragmentStreamClientsHeader.text = getString(R.string.mjpeg_stream_fragment_connected_clients).run {
            format(clientsCount).setColorSpan(colorAccent, indexOf('%'))
        }
        httpClientAdapter?.submitList(state.clients)

        binding.tvFragmentStreamTrafficHeader.text = getString(R.string.mjpeg_stream_fragment_current_traffic).run {
            format((state.traffic.lastOrNull()?.MBytes ?: 0f)).setColorSpan(colorAccent, indexOf('%'))
        }

        binding.trafficGraphFragmentStream.setDataPoints(state.traffic.map { Pair(it.time, it.MBytes) })

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

    private fun openInBrowser(fullAddress: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fullAddress)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (ex: ActivityNotFoundException) {
            Toast.makeText(requireContext().applicationContext, R.string.mjpeg_stream_fragment_no_web_browser_found, Toast.LENGTH_LONG).show()
            XLog.w(getLog("openInBrowser", ex.toString()))
        } catch (ex: SecurityException) {
            Toast.makeText(requireContext().applicationContext, R.string.mjpeg_stream_fragment_external_app_error, Toast.LENGTH_LONG).show()
            XLog.w(getLog("openInBrowser", ex.toString()))
        }
    }

    private fun shareAddress(fullAddress: String) {
        val sharingIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, fullAddress)
        }
        startActivity(Intent.createChooser(sharingIntent, getString(R.string.mjpeg_stream_fragment_share_address)))
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

    private class HttpClientAdapter : ListAdapter<MjpegState.Client, HttpClientViewHolder>(
        object : DiffUtil.ItemCallback<MjpegState.Client>() {
            override fun areItemsTheSame(oldItem: MjpegState.Client, newItem: MjpegState.Client): Boolean = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: MjpegState.Client, newItem: MjpegState.Client): Boolean = oldItem == newItem
        }
    ) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            HttpClientViewHolder(ItemMjpegClientBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemId(position: Int): Long = getItem(position).id.hashCode().toLong()

        override fun onBindViewHolder(holder: HttpClientViewHolder, position: Int) = holder.bind(getItem(position))
    }

    private class HttpClientViewHolder(private val binding: ItemMjpegClientBinding) : RecyclerView.ViewHolder(binding.root) {
        private val textColorPrimary by lazy { ContextCompat.getColor(binding.root.context, R.color.textColorPrimary) }
        private val colorError by lazy { ContextCompat.getColor(binding.root.context, R.color.colorError) }
        private val colorAccent by lazy { ContextCompat.getColor(binding.root.context, R.color.colorAccent) }

        fun bind(product: MjpegState.Client) = with(product) {
            binding.tvClientItemId.text = ""
            binding.tvClientItemAddress.text = clientAddress
            with(binding.tvClientItemStatus) {
                when (state) {
                    MjpegState.Client.State.BLOCKED -> {
                        setText(R.string.mjpeg_stream_fragment_client_blocked)
                        setTextColor(colorError)
                    }

                    MjpegState.Client.State.DISCONNECTED -> {
                        setText(R.string.mjpeg_stream_fragment_client_disconnected)
                        setTextColor(textColorPrimary)
                    }

                    MjpegState.Client.State.SLOW_CONNECTION -> {
                        setText(R.string.mjpeg_stream_fragment_client_slow_network)
                        setTextColor(colorError)
                    }

                    else -> {
                        setText(R.string.mjpeg_stream_fragment_client_connected)
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

    private fun String.setColorSpan(color: Int, start: Int = 0, end: Int = this.length) = SpannableString(this).apply {
        setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun String.setUnderlineSpan(start: Int = 0, end: Int = this.length) = SpannableString(this).apply {
        setSpan(UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}