package io.screenstream.core.compose

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.test.core.app.ActivityScenario
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** Reinstalls the same composition from onCreate, including real ActivityScenario recreation. */
internal class CoreComposeTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { checkNotNull(content).invoke() } }
    }

    internal companion object {
        var content: (@Composable () -> Unit)? = null
    }
}

internal fun launchCoreComposeHost(content: @Composable () -> Unit): ActivityScenario<CoreComposeTestActivity> {
    val application = RuntimeEnvironment.getApplication()
    shadowOf(application.packageManager).addActivityIfNotPresent(ComponentName(application, CoreComposeTestActivity::class.java))
    CoreComposeTestActivity.content = content
    return ActivityScenario.launch(CoreComposeTestActivity::class.java)
}
