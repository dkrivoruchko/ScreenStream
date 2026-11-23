package io.screenstream.core.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import io.screenstream.core.module.StreamingModule.Ui.ModalHost
import io.screenstream.core.module.StreamingModule.Ui.Section
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class StreamingModuleModalHostTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<CoreComposeTestActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
        CoreComposeTestActivity.content = null
    }

    // Cases: CORE-UI-13
    @Test
    fun readingCurrentOutsideAProviderFails() {
        val recomposer = Recomposer(EmptyCoroutineContext)
        val composition = Composition(object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun onClear() = Unit
        }, recomposer)
        try {
            assertFailsWith<IllegalStateException> {
                composition.setContent { ModalHost.current }
            }
        } finally {
            composition.dispose()
            recomposer.cancel()
        }
    }

    // Cases: CORE-UI-13
    @Test
    fun movingASectionKeepsItsModalOpenInTheDestinationHost() {
        val primary = InlineModalHost("primary pane")
        val secondary = InlineModalHost("secondary pane")
        val inSecondaryPane = mutableStateOf(false)
        val visible = mutableStateOf(true)
        val section = Section("preview", Section.Role.Overview) {
            if (visible.value) {
                ModalHost.current.Modal(
                    title = null,
                    onDismissRequest = { visible.value = false },
                ) {
                    Text("preview content")
                }
            }
        }

        scenario = launchCoreComposeHost {
            val render = remember { movableContentOf { section.content() } }
            Column {
                ModalHost.Provide(primary) { if (!inSecondaryPane.value) render() }
                ModalHost.Provide(secondary) { if (inSecondaryPane.value) render() }
            }
        }

        compose.onNodeWithText(primary.label).assertExists()
        compose.onNodeWithText(secondary.label).assertDoesNotExist()
        compose.onNodeWithText("preview content").assertExists()
        compose.runOnIdle { inSecondaryPane.value = true }

        compose.onNodeWithText(primary.label).assertDoesNotExist()
        compose.onNodeWithText(secondary.label).assertExists()
        compose.onNodeWithText("preview content").assertExists()
    }

    private class InlineModalHost(val label: String) : ModalHost {
        @Composable
        override fun Modal(title: String?, onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
            Text(label)
            content()
        }
    }
}
