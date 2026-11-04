package info.dvkr.screenstream.rtsp.ui.main.settings.server

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.rtsp.R
import info.dvkr.screenstream.rtsp.settings.generateRtspServerPassword
import info.dvkr.screenstream.rtsp.ui.main.settings.common.SettingEditorLayout
import info.dvkr.screenstream.rtsp.ui.main.settings.common.SettingSwitchRow
import info.dvkr.screenstream.rtsp.ui.main.settings.common.SettingValueRow

private const val MAX_CREDENTIAL_LENGTH: Int = 64

@Composable
internal fun ServerAuthRow(
    enabled: Boolean,
    serverAuthEnabled: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    SettingSwitchRow(
        enabled = enabled,
        checked = serverAuthEnabled,
        iconRes = R.drawable.lock_24px,
        title = stringResource(id = R.string.rtsp_pref_server_auth),
        summary = stringResource(id = R.string.rtsp_pref_server_auth_summary),
        onValueChange = onValueChange
    )
}

@Composable
internal fun ServerCredentialsRow(
    enabled: Boolean,
    username: String,
    onDetailShow: () -> Unit
) {
    SettingValueRow(
        enabled = enabled,
        iconRes = R.drawable.lock_24px,
        title = stringResource(id = R.string.rtsp_pref_server_credentials),
        summary = stringResource(id = R.string.rtsp_pref_server_credentials_summary),
        valueText = username,
        onClick = onDetailShow
    )
}

@Composable
internal fun ServerCredentialsEditor(
    username: String,
    password: String,
    onValueChange: (username: String, password: String) -> Unit
) {
    var currentUsername by remember { mutableStateOf(username) }
    var currentPassword by remember { mutableStateOf(password) }

    // Quotes, colon and control chars break Digest headers / rtsp://user:pass@ URLs in many clients
    fun String.isValidCredential(allowColon: Boolean): Boolean =
        isNotBlank() && length <= MAX_CREDENTIAL_LENGTH && none { it < ' ' || it == '"' || it == '\\' || (!allowColon && it == ':') }

    SettingEditorLayout {
        Text(
            text = stringResource(id = R.string.rtsp_pref_server_credentials_text),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = currentUsername,
            onValueChange = { value ->
                currentUsername = value.take(MAX_CREDENTIAL_LENGTH)
                if (currentUsername.isValidCredential(allowColon = false) && currentPassword.isValidCredential(allowColon = true)) {
                    onValueChange(currentUsername, currentPassword)
                }
            },
            label = { Text(text = stringResource(id = R.string.rtsp_pref_server_username)) },
            isError = currentUsername.isValidCredential(allowColon = false).not(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
            singleLine = true,
        )

        OutlinedTextField(
            value = currentPassword,
            onValueChange = { value ->
                currentPassword = value.take(MAX_CREDENTIAL_LENGTH)
                if (currentUsername.isValidCredential(allowColon = false) && currentPassword.isValidCredential(allowColon = true)) {
                    onValueChange(currentUsername, currentPassword)
                }
            },
            label = { Text(text = stringResource(id = R.string.rtsp_pref_server_password)) },
            isError = currentPassword.isValidCredential(allowColon = true).not(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            singleLine = true,
        )

        TextButton(
            onClick = {
                currentPassword = generateRtspServerPassword()
                if (currentUsername.isValidCredential(allowColon = false)) onValueChange(currentUsername, currentPassword)
            },
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            Text(text = stringResource(id = R.string.rtsp_pref_server_password_regenerate))
        }
    }
}
