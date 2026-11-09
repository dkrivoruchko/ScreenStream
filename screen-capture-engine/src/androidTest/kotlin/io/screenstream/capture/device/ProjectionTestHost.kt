package io.screenstream.capture.device

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.Display
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import io.screenstream.capture.ScreenCaptureSession
import kotlinx.coroutines.channels.Channel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Test-APK components resolve their exact run from the launch Intent, including after Activity recreation. */
internal object ProjectionTestRuns {
    const val RUN_ID: String = "projection.test.run"
    private val runs = ConcurrentHashMap<String, ProjectionTestRun>()

    fun add(run: ProjectionTestRun) {
        check(runs.putIfAbsent(run.id, run) == null)
    }

    fun get(id: String): ProjectionTestRun = checkNotNull(runs[id]) { "Unknown projection test run: $id" }

    fun remove(run: ProjectionTestRun) {
        check(runs.remove(run.id, run))
    }
}

internal class ProjectionTestRun {
    val id: String = UUID.randomUUID().toString()
    val markerSeed: Long = UUID.fromString(id).leastSignificantBits
    val changes = Channel<Unit>(Channel.CONFLATED)
    val hostFailure = AtomicReference<Throwable?>(null)
    val receipts = CopyOnWriteArrayList<String>()
    val drawGeneration = AtomicLong()

    @Volatile
    var activity: ProjectionTestActivity? = null

    @Volatile
    var service: ProjectionTestService? = null

    @Volatile
    var session: ScreenCaptureSession? = null

    @Volatile
    var scene: Int = 1

    @Volatile
    var drawn: ProjectionScene? = null

    @Volatile
    var closing: Boolean = false

    @Volatile
    var serviceDestroyed: Boolean = false
    private var consentLaunched = false
    private var consentResultReceived = false
    private var consent: Pair<Int, Intent>? = null

    @Synchronized
    fun consentLaunch() {
        check(!consentLaunched) { "A history must request consent exactly once" }
        consentLaunched = true
        receipt("consent-launched")
    }

    @Synchronized
    fun consentReturned(resultCode: Int, data: Intent?) {
        check(consentLaunched && !consentResultReceived)
        check(resultCode == Activity.RESULT_OK && data != null) { "Real projection consent was not granted: $resultCode" }
        consent = resultCode to data
        consentResultReceived = true
        receipt("consent-result=$resultCode") // Never log or persist token Intent contents.
    }

    @Synchronized
    fun takeConsent(): Pair<Int, Intent> = checkNotNull(consent).also { consent = null }

    fun receipt(value: String) {
        receipts += "${SystemClock.elapsedRealtimeNanos()} $id $value"
        signal()
    }

    fun signal() {
        changes.trySend(Unit)
    }

    fun failed(failure: Throwable) {
        hostFailure.compareAndSet(null, failure)
        signal()
    }
}

/** The deprecated two-method result API is confined to this small platform-only consent fixture. */
class ProjectionTestActivity : Activity() {
    internal lateinit var run: ProjectionTestRun
    internal lateinit var sceneView: ProjectionSceneView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        run = ProjectionTestRuns.get(checkNotNull(intent.getStringExtra(ProjectionTestRuns.RUN_ID)))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sceneView = ProjectionSceneView(this, run)
        setContentView(sceneView)
        run.activity = this
        run.receipt("activity-created=${System.identityHashCode(this)} saved=${savedInstanceState != null}")
    }

    internal fun requestConsent() {
        run.consentLaunch()
        val manager = checkNotNull(getSystemService(MediaProjectionManager::class.java))
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), CONSENT_REQUEST)
    }

    @Deprecated("Platform-only test host preserves the real Activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CONSENT_REQUEST) return
        try {
            run.consentReturned(resultCode, data)
            val serviceIntent = Intent(this, ProjectionTestService::class.java)
                .putExtra(ProjectionTestRuns.RUN_ID, run.id)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
        } catch (failure: Exception) {
            run.failed(failure)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::run.isInitialized) {
            run.receipt("activity-resumed=${System.identityHashCode(this)}")
            sceneView.invalidate()
        }
    }

    override fun onDestroy() {
        if (::run.isInitialized) {
            if (run.activity === this) run.activity = null
            run.receipt("activity-destroyed=${System.identityHashCode(this)} changing=$isChangingConfigurations")
        }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::run.isInitialized) run.signal()
    }

    private companion object {
        const val CONSENT_REQUEST: Int = 42
    }
}

/** No production component or permission is needed: the instrumentation APK owns the foreground lifetime. */
class ProjectionTestService : Service() {
    private var run: ProjectionTestRun? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val owner = ProjectionTestRuns.get(checkNotNull(intent?.getStringExtra(ProjectionTestRuns.RUN_ID)))
        try {
            check(run == null) { "Foreground service was started twice for one history" }
            run = owner
            val manager = checkNotNull(getSystemService(NotificationManager::class.java))
            val builder = if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "Capture engine test", NotificationManager.IMPORTANCE_LOW))
                Notification.Builder(this, CHANNEL)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
            val notification = builder.setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Capture engine projection test").setOngoing(true).build()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION, notification)
            }
            owner.service = this
            owner.receipt("foreground-service-promoted")
        } catch (failure: Exception) {
            owner.failed(failure)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        run?.let { owner ->
            if (!owner.closing) {
                owner.failed(IllegalStateException("Projection foreground service ended during its history"))
                owner.session?.requestStop()
            }
            owner.service = null
            owner.serviceDestroyed = true
            owner.receipt("foreground-service-destroyed")
        }
        super.onDestroy()
    }

    internal companion object {
        const val CHANNEL: String = "engine-projection-test"
        const val NOTIFICATION: Int = 4107
    }
}

internal data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

internal data class ProjectionDisplay(val width: Int, val height: Int, val density: Int, val rotation: Int)

internal data class ScenePatch(val label: String, val rect: PixelRect, val color: Int, val epoch: Boolean)

internal data class ProjectionScene(
    val runId: String,
    val epoch: Int,
    val generation: Long,
    val activityIdentity: Int,
    val display: ProjectionDisplay,
    val view: PixelRect,
    val insets: String,
    val patches: List<ScenePatch>,
)

internal object ProjectionPattern {
    val colors: List<Int> = listOf(
        0xFFE02020.toInt(), 0xFFB34D26.toInt(), 0xFF20B0C0.toInt(), 0xFF20C040.toInt(),
        0xFFC020C0.toInt(), 0xFF404040.toInt(), 0xFF808080.toInt(), 0xFFD0D0D0.toInt(),
        0xFF2040E0.toInt(), 0xFF7030B0.toInt(), 0xFF26994D.toInt(), 0xFFE0C020.toInt(),
    )
    private val epochColors = listOf(
        0xFFE02020.toInt(), 0xFF20C040.toInt(), 0xFF2040E0.toInt(), 0xFFE0C020.toInt(),
        0xFFC020C0.toInt(), 0xFF20B0C0.toInt(), 0xFFD0D0D0.toInt(), 0xFF404040.toInt(),
    )

    fun epochColor(epoch: Int, tile: Int, runSeed: Long): Int =
        epochColors[((runSeed ushr (tile * 3)).toInt() + epoch - 1) and 7]
}

internal class ProjectionSceneView(context: Context, private val run: ProjectionTestRun) : View(context) {
    private val paint = Paint().apply { isAntiAlias = false; isDither = false }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        canvas.drawColor(Color.rgb(24, 24, 24))
        val location = IntArray(2)
        getLocationOnScreen(location)
        val viewRect = PixelRect(location[0], location[1], location[0] + width, location[1] + height)
        val edge = currentInsets()
        val marginX = width / 20
        val marginY = height / 20
        val panel = PixelRect(edge[0] + marginX, edge[1] + marginY, width - edge[2] - marginX, height - edge[3] - marginY)
        check(panel.width > 0 && panel.height > 0)
        val epoch = run.scene
        val patches = ArrayList<ScenePatch>()
        for (row in 0 until 3) {
            for (column in 0 until 4) {
                val left = panel.left + panel.width * column / 4
                val right = panel.left + panel.width * (column + 1) / 4
                val top = panel.top + panel.height * row / 3
                val bottom = panel.top + panel.height * (row + 1) / 3
                val tile = PixelRect(left, top, right, bottom)
                val color = ProjectionPattern.colors[row * 4 + column]
                draw(canvas, tile, color)
                val base = PixelRect(
                    left + tile.width / 4, top + tile.height / 8,
                    right - tile.width / 4, top + tile.height * 3 / 8
                )
                val marker = PixelRect(
                    left + tile.width / 4, top + tile.height * 5 / 8,
                    right - tile.width / 4, top + tile.height * 7 / 8
                )
                val epochColor = ProjectionPattern.epochColor(epoch, row * 4 + column, run.markerSeed)
                draw(canvas, marker, epochColor)
                fun screen(rect: PixelRect): PixelRect = PixelRect(
                    rect.left + location[0], rect.top + location[1],
                    rect.right + location[0], rect.bottom + location[1]
                )
                patches += ScenePatch("base-r$row-c$column", screen(base), color, false)
                patches += ScenePatch("epoch-r$row-c$column", screen(marker), epochColor, true)
            }
        }
        run.drawn = ProjectionScene(
            run.id, epoch, run.drawGeneration.incrementAndGet(),
            System.identityHashCode(context), readProjectionDisplay(context), viewRect, edge.contentToString(), patches
        )
        run.signal()
    }

    internal fun matchesCurrentLayout(scene: ProjectionScene): Boolean {
        val location = IntArray(2)
        getLocationOnScreen(location)
        val currentView = PixelRect(location[0], location[1], location[0] + width, location[1] + height)
        return scene.view == currentView && scene.insets == currentInsets().contentToString()
    }

    private fun currentInsets(): IntArray {
        val rootInsets = rootWindowInsets
        return if (Build.VERSION.SDK_INT >= 30 && rootInsets != null) {
            val inset = rootInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            intArrayOf(inset.left, inset.top, inset.right, inset.bottom)
        } else {
            @Suppress("DEPRECATION")
            intArrayOf(
                rootInsets?.systemWindowInsetLeft ?: 0, rootInsets?.systemWindowInsetTop ?: 0,
                rootInsets?.systemWindowInsetRight ?: 0, rootInsets?.systemWindowInsetBottom ?: 0
            )
        }
    }

    private fun draw(canvas: Canvas, rect: PixelRect, color: Int) {
        paint.color = color
        canvas.drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), paint)
    }
}

@Suppress("DEPRECATION")
internal fun readProjectionDisplay(context: Context): ProjectionDisplay {
    val display = checkNotNull(context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY))
    check(display.isValid)
    val point = Point()
    display.getRealSize(point)
    return ProjectionDisplay(point.x, point.y, context.resources.configuration.densityDpi, display.rotation)
}
