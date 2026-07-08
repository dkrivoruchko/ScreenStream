package dev.dmkr.screencaptureengine.internal.platform.projection

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupProjectionCallbackRouterTest {
    @Test
    fun routerSuppressesStartupResizeWhenStopArrivesAfterDispatchPreparation() {
        val startupSink = RecordingStartupSink()
        lateinit var router: StartupProjectionCallbackRouter
        router = StartupProjectionCallbackRouter(
            startupSink = startupSink,
            beforeNonTerminalDispatch = { router.onProjectionStopped() },
        )

        router.onCapturedContentResized(ProjectionCapturedContentResize(id = 1L, width = 800, height = 600))

        assertEquals(1, startupSink.stopCount)
        assertEquals(emptyList<ProjectionCapturedContentResize>(), startupSink.resizes)
    }


    @Test
    fun routerSuppressesStartupVisibilityWhenStopArrivesAfterDispatchPreparation() {
        val startupSink = RecordingStartupSink()
        lateinit var router: StartupProjectionCallbackRouter
        router = StartupProjectionCallbackRouter(
            startupSink = startupSink,
            beforeNonTerminalDispatch = { router.onProjectionStopped() },
        )

        router.onCapturedContentVisibilityChanged(isVisible = false)

        assertEquals(1, startupSink.stopCount)
        assertEquals(emptyList<Boolean>(), startupSink.visibilityChanges)
    }


    @Test
    fun routerSuppressesRuntimeResizeWhenStopArrivesAfterDispatchPreparation() {
        val startupSink = RecordingStartupSink()
        val runtimeListener = RecordingProjectionCallbackListener()
        lateinit var router: StartupProjectionCallbackRouter
        router = StartupProjectionCallbackRouter(
            startupSink = startupSink,
            beforeNonTerminalDispatch = { router.onProjectionStopped() },
        )
        router.handoffTo(runtimeListener)

        router.onCapturedContentResized(ProjectionCapturedContentResize(id = 1L, width = 800, height = 600))

        assertEquals(0, startupSink.stopCount)
        assertEquals(1, runtimeListener.stopCount)
        assertEquals(emptyList<ProjectionCapturedContentResize>(), runtimeListener.resizes)
    }


    @Test
    fun routerSuppressesRuntimeVisibilityWhenStopArrivesAfterDispatchPreparation() {
        val startupSink = RecordingStartupSink()
        val runtimeListener = RecordingProjectionCallbackListener()
        lateinit var router: StartupProjectionCallbackRouter
        router = StartupProjectionCallbackRouter(
            startupSink = startupSink,
            beforeNonTerminalDispatch = { router.onProjectionStopped() },
        )
        router.handoffTo(runtimeListener)

        router.onCapturedContentVisibilityChanged(isVisible = false)

        assertEquals(0, startupSink.stopCount)
        assertEquals(1, runtimeListener.stopCount)
        assertEquals(emptyList<Boolean>(), runtimeListener.visibilityChanges)
    }


    @Test
    fun routerSuppressesRuntimeResizeWhenClosedAfterDispatchPreparation() {
        val startupSink = RecordingStartupSink()
        val runtimeListener = RecordingProjectionCallbackListener()
        lateinit var router: StartupProjectionCallbackRouter
        router = StartupProjectionCallbackRouter(
            startupSink = startupSink,
            beforeNonTerminalDispatch = { router.close() },
        )
        router.handoffTo(runtimeListener)

        router.onCapturedContentResized(ProjectionCapturedContentResize(id = 1L, width = 800, height = 600))

        assertEquals(emptyList<ProjectionCapturedContentResize>(), runtimeListener.resizes)
    }


    @Test
    fun routerForwardsDelayedRuntimeResizeToReplacementListener() {
        val startupSink = RecordingStartupSink()
        val oldRuntimeListener = RecordingProjectionCallbackListener()
        val newRuntimeListener = RecordingProjectionCallbackListener()
        var replaced = false
        lateinit var router: StartupProjectionCallbackRouter
        router = StartupProjectionCallbackRouter(
            startupSink = startupSink,
            beforeNonTerminalDispatch = {
                if (!replaced) {
                    replaced = true
                    router.replaceRuntimeListener(
                        expectedCurrent = oldRuntimeListener,
                        replacement = newRuntimeListener,
                    )
                }
            },
        )
        router.handoffTo(oldRuntimeListener)

        router.onCapturedContentResized(ProjectionCapturedContentResize(id = 1L, width = 800, height = 600))

        assertEquals(emptyList<ProjectionCapturedContentResize>(), oldRuntimeListener.resizes)
        assertEquals(listOf(ProjectionCapturedContentResize(id = 1L, width = 800, height = 600)), newRuntimeListener.resizes)
    }


    @Test
    fun routerDispatchesRuntimeCallbacksThroughSelectedRuntimeListenerPath() {
        val startupSink = RecordingStartupSink()
        val runtimeListener = RecordingSelectedProjectionCallbackListener()
        val router = StartupProjectionCallbackRouter(startupSink = startupSink)
        router.handoffTo(runtimeListener)

        router.onCapturedContentResized(ProjectionCapturedContentResize(id = 1L, width = 800, height = 600))
        router.onCapturedContentVisibilityChanged(isVisible = false)
        router.onProjectionStopped()

        assertEquals(0, runtimeListener.normalStopCount)
        assertEquals(emptyList<ProjectionCapturedContentResize>(), runtimeListener.normalResizes)
        assertEquals(emptyList<Boolean>(), runtimeListener.normalVisibilityChanges)
        assertEquals(1, runtimeListener.selectedStopCount)
        assertEquals(listOf(ProjectionCapturedContentResize(id = 1L, width = 800, height = 600)), runtimeListener.selectedResizes)
        assertEquals(listOf(false), runtimeListener.selectedVisibilityChanges)
    }


    private class RecordingStartupSink : StartupProjectionCallbackRouter.StartupSink {
        var stopCount = 0
        val resizes = mutableListOf<ProjectionCapturedContentResize>()
        val visibilityChanges = mutableListOf<Boolean>()

        override fun onProjectionStopped() {
            stopCount++
        }

        override fun onCapturedContentResized(resize: ProjectionCapturedContentResize) {
            resizes += resize
        }

        override fun onCapturedContentResized(width: Int, height: Int) {
            resizes += ProjectionCapturedContentResize(id = 0L, width = width, height = height)
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            visibilityChanges += isVisible
        }
    }

    private class RecordingProjectionCallbackListener : MediaProjectionCallbackAdapter.Listener {
        var stopCount = 0
        val resizes = mutableListOf<ProjectionCapturedContentResize>()
        val visibilityChanges = mutableListOf<Boolean>()

        override fun onProjectionStopped() {
            stopCount++
        }

        override fun onCapturedContentResized(resize: ProjectionCapturedContentResize) {
            resizes += resize
        }

        override fun onCapturedContentResized(width: Int, height: Int) {
            resizes += ProjectionCapturedContentResize(id = 0L, width = width, height = height)
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            visibilityChanges += isVisible
        }
    }

    private class RecordingSelectedProjectionCallbackListener : StartupProjectionCallbackRouter.SelectedRuntimeListener {
        var normalStopCount = 0
        var selectedStopCount = 0
        val normalResizes = mutableListOf<ProjectionCapturedContentResize>()
        val selectedResizes = mutableListOf<ProjectionCapturedContentResize>()
        val normalVisibilityChanges = mutableListOf<Boolean>()
        val selectedVisibilityChanges = mutableListOf<Boolean>()

        override fun onProjectionStopped() {
            normalStopCount++
        }

        override fun onCapturedContentResized(resize: ProjectionCapturedContentResize) {
            normalResizes += resize
        }

        override fun onCapturedContentResized(width: Int, height: Int) {
            normalResizes += ProjectionCapturedContentResize(id = 0L, width = width, height = height)
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            normalVisibilityChanges += isVisible
        }

        override fun onRouterSelectedProjectionStopped() {
            selectedStopCount++
        }

        override fun onRouterSelectedCapturedContentResized(resize: ProjectionCapturedContentResize) {
            selectedResizes += resize
        }

        override fun onRouterSelectedCapturedContentVisibilityChanged(isVisible: Boolean) {
            selectedVisibilityChanges += isVisible
        }
    }
}
