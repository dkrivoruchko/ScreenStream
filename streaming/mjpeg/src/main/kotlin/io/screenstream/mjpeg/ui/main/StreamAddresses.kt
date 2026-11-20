package io.screenstream.mjpeg.ui.main

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.screenstream.core.compose.LocalRuntimePermissions
import io.screenstream.core.permission.PermissionState
import io.screenstream.core.permission.RuntimePermission
import io.screenstream.foundation.qr.generateQRBitmap
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.networkaddress.AddressFamily
import io.screenstream.mjpeg.networkaddress.InterfaceType
import io.screenstream.mjpeg.settings.SecretValue
import io.screenstream.mjpeg.ui.ExpandableCard
import io.screenstream.mjpeg.ui.UiController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

@Composable
internal fun StreamAddressesContent(
    addressServers: List<UiController.AddressServer>,
    appliedPin: SecretValue?,
    hidePinOnCapture: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onRetry: (Uuid) -> Unit,
    onShowMessage: (String) -> Unit,
) {
    fun isBlockedByLocalNetworkPermission(server: UiController.AddressServer): Boolean =
        server.serverId == null &&
                (server.status as? UiController.ServerStatus.Failed)?.reason == UiController.ServerFailureReason.PermissionDenied

    ExpandableCard(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = Modifier.fillMaxWidth(),
        contentModifier = Modifier
            .fillMaxWidth()
            .background(ListItemDefaults.containerColor),
        headerContent = {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 48.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.mjpeg_icon_interfaces),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = stringResource(R.string.mjpeg_address_list_title),
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        },
    ) {
        val runtimePermissions = if (addressServers.any(::isBlockedByLocalNetworkPermission)) LocalRuntimePermissions.current else null
        val localNetworkPermissionState = runtimePermissions?.let { permissions ->
            val state = permissions.state(RuntimePermission.LocalNetwork).collectAsStateWithLifecycle().value
            if (permissions.isGranted(RuntimePermission.LocalNetwork)) null else state
        }
        val onRequestLocalNetworkPermission: () -> Unit = { runtimePermissions?.tryRequest(RuntimePermission.LocalNetwork) }

        if (addressServers.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.mjpeg_icon_warning),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.mjpeg_address_list_empty),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        addressServers.forEachIndexed { index, server ->
            key(server.addressId) {
                StreamAddressRow(
                    server = server,
                    blockedByLocalNetworkPermission = isBlockedByLocalNetworkPermission(server),
                    localNetworkPermissionState = localNetworkPermissionState,
                    onRequestLocalNetworkPermission = onRequestLocalNetworkPermission,
                    onRetry = onRetry,
                    onShowMessage = onShowMessage,
                )
            }
            if (index != addressServers.lastIndex) {
                HorizontalDivider()
            }
        }
        appliedPin?.let { pin ->
            HorizontalDivider()
            StreamPinContent(
                pin = pin,
                hidePinOnCapture = hidePinOnCapture,
                onShowMessage = onShowMessage,
            )
        }
    }
}

@Composable
private fun StreamAddressRow(
    server: UiController.AddressServer,
    blockedByLocalNetworkPermission: Boolean,
    localNetworkPermissionState: PermissionState?,
    onRequestLocalNetworkPermission: () -> Unit,
    onRetry: (Uuid) -> Unit,
    onShowMessage: (String) -> Unit,
) {
    val urlHost = if (':' in server.address) "[${server.address}]" else server.address
    val displayUrl = "http://$urlHost:${server.port}/"
    val actionUrl = server.accessUrl?.value ?: displayUrl
    val context = LocalContext.current
    val browserNotFoundMessage = stringResource(R.string.mjpeg_address_error_browser_not_found)
    val openErrorMessage = stringResource(R.string.mjpeg_address_error_open)
    val onOpen: () -> Unit = {
        try {
            val intent = Intent(Intent.ACTION_VIEW, actionUrl.toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            onShowMessage(browserNotFoundMessage)
        } catch (_: SecurityException) {
            onShowMessage(openErrorMessage)
        }
    }

    val interfaceLabelRes = if (server.isLoopback) {
        R.string.mjpeg_network_loopback
    } else {
        when (server.interfaceType) {
            InterfaceType.Wifi -> R.string.mjpeg_network_wifi
            InterfaceType.Ethernet -> R.string.mjpeg_network_ethernet
            InterfaceType.Mobile -> R.string.mjpeg_network_mobile
            InterfaceType.Vpn -> R.string.mjpeg_network_vpn
            InterfaceType.Other -> R.string.mjpeg_network_other
        }
    }
    val addressScopeLabelRes = when (server.family) {
        AddressFamily.Ipv4 -> when {
            server.isLoopback -> R.string.mjpeg_network_ipv4
            server.isLinkLocal -> R.string.mjpeg_network_ipv4_link_local
            server.isPrivate -> R.string.mjpeg_address_private_ipv4
            else -> R.string.mjpeg_address_non_private_ipv4
        }

        AddressFamily.Ipv6 -> when {
            server.isLoopback -> R.string.mjpeg_network_ipv6
            server.isPrivate -> R.string.mjpeg_address_ipv6_ula
            else -> R.string.mjpeg_address_non_private_ipv6
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
    ) {
        TextButton(
            onClick = onOpen,
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        ) {
            Text(displayUrl, style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Ltr))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(
                    R.string.mjpeg_address_network_details,
                    stringResource(interfaceLabelRes),
                    stringResource(addressScopeLabelRes),
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StreamAddressActions(
                displayUrl = displayUrl,
                actionUrl = actionUrl,
                onShowMessage = onShowMessage,
                modifier = Modifier.weight(1.5f),
            )
        }
        if (server.isLoopback) {
            Text(text = stringResource(R.string.mjpeg_address_same_device), style = MaterialTheme.typography.bodySmall)
        }
        when (val status = server.status) {
            UiController.ServerStatus.Listening -> Unit
            UiController.ServerStatus.Pending -> Text(
                text = stringResource(R.string.mjpeg_server_pending),
                style = MaterialTheme.typography.bodySmall,
            )

            is UiController.ServerStatus.Failed -> if (blockedByLocalNetworkPermission) {
                if (localNetworkPermissionState != null) {
                    LocalNetworkPermissionButton(localNetworkPermissionState, onRequestLocalNetworkPermission)
                }
            } else {
                StreamAddressFailure(
                    reason = status.reason,
                    serverId = server.serverId,
                    displayUrl = displayUrl,
                    onRetry = onRetry,
                )
            }
        }
    }
}

@Composable
private fun StreamAddressActions(
    displayUrl: String,
    actionUrl: String,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val copyLabel = stringResource(R.string.mjpeg_address_copy_url)
    val copiedMessage = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) stringResource(R.string.mjpeg_address_copied) else null
    val shareTitle = stringResource(R.string.mjpeg_address_share)
    val shareErrorMessage = stringResource(R.string.mjpeg_address_error_share)
    var showCopyMenu by remember(actionUrl) { mutableStateOf(false) }
    var showQrCode by remember(actionUrl) { mutableStateOf(false) }
    val copyLink: (String) -> Unit = { url ->
        scope.launch {
            val clip = ClipData.newPlainText(copyLabel, url)
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
            clipboard.setClipEntry(ClipEntry(clip))
            copiedMessage?.let(onShowMessage)
        }
    }

    val actionShape = MaterialTheme.shapes.medium
    val iconColors = IconButtonDefaults.filledTonalIconButtonColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val shareLink: () -> Unit = {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, actionUrl)
            }
            val chooser = Intent.createChooser(intent, shareTitle)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            onShowMessage(shareErrorMessage)
        } catch (_: SecurityException) {
            onShowMessage(shareErrorMessage)
        }
    }

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        SplitButtonLayout(
            leadingButton = {
                FilledTonalIconButton(
                    onClick = { copyLink(actionUrl) },
                    shape = actionShape,
                    colors = iconColors,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.mjpeg_icon_copy),
                        contentDescription = stringResource(R.string.mjpeg_address_copy_description, displayUrl),
                    )
                }
            },
            trailingButton = {
                Box {
                    FilledTonalIconButton(
                        onClick = { showCopyMenu = true },
                        shape = actionShape,
                        colors = iconColors,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.mjpeg_icon_arrow_drop_down),
                            contentDescription = stringResource(R.string.mjpeg_address_copy_options_description, displayUrl),
                        )
                    }
                    DropdownMenu(expanded = showCopyMenu, onDismissRequest = { showCopyMenu = false }) {
                        listOf(
                            R.string.mjpeg_address_copy_page to "/",
                            R.string.mjpeg_address_copy_mjpeg to "/mjpeg",
                            R.string.mjpeg_address_copy_jpeg to "/jpeg",
                        ).forEach { (label, path) ->
                            DropdownMenuItem(
                                text = { Text(stringResource(label)) },
                                onClick = {
                                    showCopyMenu = false
                                    copyLink(actionUrl.toUri().buildUpon().path(path).build().toString())
                                },
                            )
                        }
                    }
                }
            },
        )
        Row {
            FilledTonalIconButton(onClick = shareLink, shape = actionShape, colors = iconColors) {
                Icon(
                    painter = painterResource(R.drawable.mjpeg_icon_share),
                    contentDescription = stringResource(R.string.mjpeg_address_share_description, displayUrl),
                )
            }
            FilledTonalIconButton(onClick = { showQrCode = true }, shape = actionShape, colors = iconColors) {
                Icon(
                    painter = painterResource(R.drawable.mjpeg_icon_qr),
                    contentDescription = stringResource(R.string.mjpeg_address_qr_code_description, displayUrl),
                )
            }
        }
    }
    if (showQrCode) {
        QrCodeDialog(url = actionUrl, onDismiss = { showQrCode = false })
    }
}

@Composable
private fun StreamAddressFailure(
    reason: UiController.ServerFailureReason,
    serverId: Uuid?,
    displayUrl: String,
    onRetry: (Uuid) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(
                if (reason == UiController.ServerFailureReason.PermissionDenied) {
                    R.drawable.mjpeg_icon_permission_warning
                } else {
                    R.drawable.mjpeg_icon_warning
                }
            ),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            text = stringResource(
                when (reason) {
                    UiController.ServerFailureReason.AddressUnavailable -> R.string.mjpeg_server_address_unavailable
                    UiController.ServerFailureReason.PermissionDenied -> R.string.mjpeg_server_permission_denied
                    UiController.ServerFailureReason.IoFailure -> R.string.mjpeg_server_io_failure
                    UiController.ServerFailureReason.Unknown -> R.string.mjpeg_server_unknown_failure
                },
            ),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        if (serverId != null) {
            IconButton(
                onClick = { onRetry(serverId) },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.mjpeg_icon_retry),
                    contentDescription = stringResource(R.string.mjpeg_server_retry_description, displayUrl),
                )
            }
        }
    }
}

@Composable
private fun LocalNetworkPermissionButton(
    permissionState: PermissionState,
    onClick: () -> Unit,
) {
    val opensSettings = permissionState == PermissionState.SettingsSuggested

    FilledTonalButton(
        onClick = onClick,
        enabled = permissionState != PermissionState.Requesting,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.mjpeg_icon_permission_warning),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(8.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = stringResource(if (opensSettings) R.string.mjpeg_local_network_permission_open_settings else R.string.mjpeg_local_network_permission_request),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.mjpeg_local_network_permission_required),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Icon(
            painter = painterResource(if (opensSettings) R.drawable.mjpeg_icon_open else R.drawable.mjpeg_icon_arrow_forward),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** The producer is composition-owned; cancellation discards any late encoding result. */
@Composable
private fun QrCodeDialog(
    url: String,
    onDismiss: () -> Unit,
) {
    var qrCodeSizePx by remember { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.semantics(mergeDescendants = true) {
                dismiss {
                    onDismiss()
                    true
                }
            },
            shape = MaterialTheme.shapes.large,
            color = Color.White,
        ) {
            Box(
                modifier = Modifier
                    .sizeIn(maxWidth = 208.dp, maxHeight = 208.dp)
                    .aspectRatio(1f)
                    .onSizeChanged { qrCodeSizePx = it.width },
                contentAlignment = Alignment.Center,
            ) {
                val imageSizePx = qrCodeSizePx
                key(url, imageSizePx) {
                    val qrCode = produceState<Result<ImageBitmap>?>(initialValue = null) {
                        if (imageSizePx <= 0) return@produceState
                        value = try {
                            val bitmap = withContext(Dispatchers.Default) {
                                url.generateQRBitmap(imageSizePx).asImageBitmap()
                            }
                            Result.success(bitmap)
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (failure: Exception) {
                            Result.failure(failure)
                        }
                    }.value
                    val bitmap = qrCode?.getOrNull()
                    when {
                        qrCode == null -> CircularProgressIndicator(modifier = Modifier.size(32.dp))

                        bitmap == null -> Text(
                            text = stringResource(R.string.mjpeg_address_error_qr_generation),
                            color = MaterialTheme.colorScheme.error,
                        )

                        else -> Image(
                            bitmap = bitmap,
                            contentDescription = stringResource(R.string.mjpeg_address_qr_image_description),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.None,
                            filterQuality = FilterQuality.None,
                        )
                    }
                }
            }
        }
    }
}
