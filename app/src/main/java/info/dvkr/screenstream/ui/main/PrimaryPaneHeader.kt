package info.dvkr.screenstream.ui.main

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.repeatOnLifecycle
import info.dvkr.screenstream.R
import info.dvkr.screenstream.ads.AdMob
import info.dvkr.screenstream.ui.AdaptiveAppModal
import info.dvkr.screenstream.ui.AppDialog
import info.dvkr.screenstream.ui.settings.SettingsModal
import io.screenstream.core.StreamingModuleManager
import io.screenstream.core.compose.LocalRuntimePermissions
import io.screenstream.core.module.StreamingModule
import io.screenstream.core.permission.PermissionState
import io.screenstream.core.permission.RuntimePermission
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private enum class PrimaryPaneOverlay { MethodChooser, Settings, About, ExitConfirmation }

private val StreamingModuleManager.State.allowsMethodSelection: Boolean
    get() = when (this) {
        is StreamingModuleManager.State.Running,
        is StreamingModuleManager.State.Unresponsive,
        is StreamingModuleManager.State.Failed -> true

        else -> false
    }

@Composable
internal fun PrimaryPaneHeader(
    streamingModuleManager: StreamingModuleManager,
    streamingModuleManagerState: StreamingModuleManager.State,
    inlineMethodSelector: Boolean,
    headerDialogBounds: DpRect?,
    contentDialogBounds: DpRect?,
    initialMethodChooserPending: MutableState<Boolean>,
) {
    // The movable header gives retained state a stable position across pane changes.
    var activeOverlay by retain { mutableStateOf<PrimaryPaneOverlay?>(null) }
    val scope = rememberCoroutineScope()
    val methodSelectionEnabled = streamingModuleManagerState.allowsMethodSelection

    fun openOverlay(overlay: PrimaryPaneOverlay) {
        if (activeOverlay != null) return
        val state = streamingModuleManager.state.value
        val isMethodChooser = overlay == PrimaryPaneOverlay.MethodChooser
        if (isMethodChooser) {
            if (!methodSelectionEnabled || !state.allowsMethodSelection) return
        } else if (state == StreamingModuleManager.State.Closing || state == StreamingModuleManager.State.Closed) {
            return
        }
        activeOverlay = overlay
        if (isMethodChooser) initialMethodChooserPending.value = false
    }

    if (initialMethodChooserPending.value && activeOverlay == null && methodSelectionEnabled) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val notificationPermission = LocalRuntimePermissions.current.state(RuntimePermission.Notifications)
        LaunchedEffect(lifecycle, notificationPermission, streamingModuleManager) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                combine(notificationPermission, streamingModuleManager.state) { permission, state ->
                    permission != PermissionState.Requesting && state.allowsMethodSelection
                }.first { ready ->
                    if (ready && initialMethodChooserPending.value && notificationPermission.value != PermissionState.Requesting) {
                        openOverlay(PrimaryPaneOverlay.MethodChooser)
                    }
                    // Keep waiting if either live check blocked the opening.
                    !initialMethodChooserPending.value
                }
            }
        }
    }

    val selectedModuleId = streamingModuleManagerState.instanceId?.moduleId
    val isOpening = streamingModuleManagerState == StreamingModuleManager.State.Opening
    var lastRenderedModuleId by retain { mutableStateOf<StreamingModule.Id?>(null) }
    val displayedModuleId = if (isOpening) lastRenderedModuleId else selectedModuleId
    val displayedMethodDescription = streamingModuleManager.modules.firstOrNull { it.id == displayedModuleId }?.description
    if (!isOpening) SideEffect { lastRenderedModuleId = selectedModuleId }

    AppHeader(
        streamingModuleDescription = displayedMethodDescription,
        methodSelectionEnabled = methodSelectionEnabled,
        inlineMethodSelector = inlineMethodSelector,
        onChooseMethod = dropUnlessResumed { openOverlay(PrimaryPaneOverlay.MethodChooser) },
        onOpenSettings = dropUnlessResumed { openOverlay(PrimaryPaneOverlay.Settings) },
        onOpenAbout = dropUnlessResumed { openOverlay(PrimaryPaneOverlay.About) },
        onRequestExit = dropUnlessResumed { openOverlay(PrimaryPaneOverlay.ExitConfirmation) },
        modifier = Modifier.padding(horizontal = 12.dp)
    )

    val displayedOverlay = activeOverlay ?: return
    val dismissOverlay = {
        if (activeOverlay == displayedOverlay) activeOverlay = null
    }
    when (displayedOverlay) {
        PrimaryPaneOverlay.MethodChooser -> {
            if (!methodSelectionEnabled) {
                SideEffect { dismissOverlay() }
                return
            }

            StreamingMethodChooserModal(
                modules = streamingModuleManager.modules,
                selectedModuleId = selectedModuleId,
                onMethodSelect = { moduleId ->
                    scope.launch {
                        if (activeOverlay == PrimaryPaneOverlay.MethodChooser && streamingModuleManager.state.value.allowsMethodSelection) {
                            activeOverlay = null
                            streamingModuleManager.selectModule(moduleId)
                        }
                    }
                },
                onDismissRequest = dismissOverlay,
                dialogBounds = headerDialogBounds,
            )
        }

        PrimaryPaneOverlay.Settings -> SettingsModal(onDismissRequest = dismissOverlay, dialogBounds = contentDialogBounds)
        PrimaryPaneOverlay.About -> AboutModal(onDismissRequest = dismissOverlay, dialogBounds = contentDialogBounds)
        PrimaryPaneOverlay.ExitConfirmation -> ExitConfirmationModal(
            onDismissRequest = dismissOverlay,
            onConfirm = dropUnlessResumed {
                if (activeOverlay == PrimaryPaneOverlay.ExitConfirmation) {
                    activeOverlay = null
                    streamingModuleManager.exit()
                }
            },
            dialogBounds = headerDialogBounds,
        )
    }
}

@Composable
internal fun AboutModal(onDismissRequest: () -> Unit, dialogBounds: DpRect?) {
    val scroll = retain { ScrollState(0) }
    AdaptiveAppModal(title = stringResource(R.string.app_about_title), onDismissRequest = onDismissRequest, dialogBounds = dialogBounds) {
        val adMob = koinInject<AdMob>()
        val context = LocalContext.current
        val activity = LocalActivity.current
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val scope = rememberCoroutineScope()

        fun tryOpenUrl(url: String): Boolean {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(intent)
                true
            } catch (_: ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }

        fun openUrl(url: String, fallbackUrl: String? = null) {
            if (tryOpenUrl(url) || (fallbackUrl != null && tryOpenUrl(fallbackUrl))) return
            Toast.makeText(context, R.string.app_about_open_link_error, Toast.LENGTH_SHORT).show()
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scroll),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = null,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(104.dp)
                    .clip(MaterialTheme.shapes.medium)
            )

            Text(
                text = stringResource(id = R.string.app_name),
                modifier = Modifier.padding(top = 16.dp),
                maxLines = 1,
                style = MaterialTheme.typography.headlineSmall
            )

            val versionName = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "unknown"
            Text(
                text = stringResource(id = R.string.app_about_version, versionName),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = stringResource(id = R.string.app_about_developer),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Column(modifier = Modifier.width(IntrinsicSize.Max)) {
                val privacyOptionsAction = if (adMob.isPrivacyOptionsRequired && activity != null) {
                    Triple(
                        R.drawable.app_icon_settings,
                        R.string.app_about_privacy_options,
                        dropUnlessResumed {
                            adMob.showPrivacyOptionsForm(activity) {
                                if (scope.isActive && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                                    Toast.makeText(
                                        context,
                                        R.string.app_about_privacy_options_open_error,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                    )
                } else {
                    null
                }
                val sourceUrl = "https://github.com/dkrivoruchko/ScreenStream"
                val actions = listOfNotNull(
                    Triple(R.drawable.app_icon_star_rate, R.string.app_about_rate_app) {
                        openUrl(
                            "market://details?id=${context.packageName}",
                            fallbackUrl = "https://play.google.com/store/apps/details?id=${context.packageName}",
                        )
                    },
                    Triple(R.drawable.app_icon_github, R.string.app_about_source_code) {
                        openUrl(sourceUrl)
                    },
                    Triple(R.drawable.app_icon_receipt_long, R.string.app_about_terms_and_conditions) {
                        openUrl("$sourceUrl/blob/master/TermsConditions.md")
                    },
                    Triple(R.drawable.app_icon_privacy_tip, R.string.app_about_privacy_policy) {
                        openUrl("$sourceUrl/blob/master/PrivacyPolicy.md")
                    },
                    privacyOptionsAction,
                    Triple(R.drawable.app_icon_license, R.string.app_about_license) {
                        openUrl("$sourceUrl/blob/master/LICENSE")
                    },
                )
                for ((iconRes, labelRes, onClick) in actions) {
                    key(labelRes) {
                        TextButton(
                            onClick = onClick,
                            modifier = Modifier
                                .padding(vertical = 4.dp)
                                .fillMaxWidth(),
                        ) {
                            Icon(
                                painter = painterResource(iconRes),
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text(text = stringResource(labelRes), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExitConfirmationModal(onDismissRequest: () -> Unit, onConfirm: () -> Unit, dialogBounds: DpRect?) {
    AppDialog(title = stringResource(R.string.app_exit_dialog_title), onDismissRequest = onDismissRequest, dialogBounds = dialogBounds) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.app_exit_dialog_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.app_exit_dialog_message), modifier = Modifier.weight(1f, fill = false))
            FlowRow(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismissRequest) { Text(stringResource(android.R.string.cancel)) }
                Button(onClick = onConfirm) { Text(stringResource(R.string.app_action_exit)) }
            }
        }
    }
}
