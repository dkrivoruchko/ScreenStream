package dev.dmkr.screencaptureengine.internal.target

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import dev.dmkr.screencaptureengine.internal.gl.BlockingProjectionTargetGlAccess
import dev.dmkr.screencaptureengine.internal.gl.CleanupFailureCollector
import dev.dmkr.screencaptureengine.internal.gl.GlLaneAbandonment
import dev.dmkr.screencaptureengine.internal.gl.GlLaneContextOwner
import dev.dmkr.screencaptureengine.internal.gl.GlLaneScope
import dev.dmkr.screencaptureengine.internal.gl.GlResourceRetirementLane
import dev.dmkr.screencaptureengine.internal.gl.ProjectionTargetGlLane
import dev.dmkr.screencaptureengine.internal.lifecycle.RuntimeFrameSignalSink
import dev.dmkr.screencaptureengine.internal.lifecycle.RuntimeProjectionTargetFrameAvailableListener
import dev.dmkr.screencaptureengine.internal.platform.projection.AndroidProjectionSurfaceHandle
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionSurfaceHandle
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetHandle
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetOwnerHandle
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetSizeLimits
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Owns generation-numbered projection targets backed by a GL external OES texture.
 *
 * Each target owns its `SurfaceTexture`, projection `Surface`, and OES texture until the target or
 * owner is closed. The GL lane and current EGL context are owned by [GlLaneContextOwner]. Startup
 * rendering access exposes only the current target for the expected generation and reports
 * owner/generation/closed-target mismatches as typed startup GL access invariants.
 */
@OptIn(BlockingProjectionTargetGlAccess::class)
internal class ProjectionTargetOwner internal constructor(
    private val glLane: ProjectionTargetGlLane,
) : ProjectionTargetOwnerHandle,
    ProjectionTargetGlCapability,
    RuntimeProjectionTargetGlAccess,
    StartupRenderingGlAccess,
    ProjectionTargetOwnerAbandonment {
    internal constructor(
        threadName: String = "ScreenCaptureProjectionTarget",
    ) : this(GlLaneContextOwner(threadName))

    private val stateLock = ReentrantLock()
    private val noActiveWork = stateLock.newCondition()
    private val runtimeOwnerIdentity = RuntimeProjectionTargetOwnerIdentity()
    private val liveTargets = LinkedHashSet<ProjectionTarget>()
    private var isClosed = false
    private var isAbandoned = false
    private var activeCreations = 0
    private var activeReleases = 0
    private var nextGeneration = 1L

    override val isGlLaneAbandoned: Boolean
        get() = isGlLaneAbandonedForOwner()

    override fun startupRenderingGlAccess(): StartupRenderingGlAccess = this

    override fun targetSizeLimits(): ProjectionTargetSizeLimits {
        checkOpen()
        return glLane.executeCurrentBlocking {
            checkOpen()
            targetSizeLimits()
        }
    }

    override fun createTarget(width: Int, height: Int, densityDpi: Int): ProjectionTarget {
        require(width > 0) { "width must be positive, was $width" }
        require(height > 0) { "height must be positive, was $height" }
        require(densityDpi > 0) { "densityDpi must be positive, was $densityDpi" }

        val generation = stateLock.withLock {
            checkOpenLocked()
            val value = nextGeneration
            nextGeneration = Math.addExact(value, 1L)
            activeCreations += 1
            value
        }

        try {
            val target = glLane.executeCurrentBlocking {
                checkOpen()
                createTargetOnGlThread(
                    gl = this,
                    generation = generation,
                    width = width,
                    height = height,
                    densityDpi = densityDpi,
                )
            }
            var shouldReleaseCreatedTarget: ProjectionTarget? = null
            stateLock.withLock {
                if (isClosed || isAbandoned) {
                    shouldReleaseCreatedTarget = target
                    check(target.markClosedForOwner())
                } else {
                    liveTargets.add(target)
                }
            }
            shouldReleaseCreatedTarget?.let { createdTarget ->
                createdTarget.releaseOwnedResources()
                throw IllegalStateException("ProjectionTargetOwner is closed.")
            }
            return target
        } finally {
            stateLock.withLock {
                activeCreations -= 1
                noActiveWork.signalAll()
            }
        }
    }

    override fun close() {
        check(!glLane.isOnGlThread()) { "ProjectionTargetOwner.close must not be called from its GL thread." }
        val targetsToRelease = stateLock.withLock {
            if (isClosed) return
            isClosed = true
            while (!isAbandoned && ((activeCreations > 0) || (activeReleases > 0))) {
                noActiveWork.awaitUninterruptibly()
            }
            liveTargets.filter(ProjectionTarget::markClosedForOwner).also { targets ->
                liveTargets.clear()
                activeReleases += targets.size
            }
        }
        val cleanupFailures = CleanupFailureCollector()
        try {
            targetsToRelease.forEach { target ->
                cleanupFailures.collect(target::releaseOwnedResources)
            }
        } finally {
            stateLock.withLock {
                activeReleases -= targetsToRelease.size
                noActiveWork.signalAll()
            }
        }
        cleanupFailures.collect(glLane::close)
        cleanupFailures.throwIfAny()
    }

    override fun abandonGlLane() {
        stateLock.withLock {
            isAbandoned = true
            noActiveWork.signalAll()
        }
        (glLane as? GlLaneAbandonment)?.abandonGlLane()
    }

    override suspend fun <T> withCurrentStartupRenderingTarget(
        target: ProjectionTargetHandle,
        generation: Long,
        onCancellation: (T) -> Unit,
        block: StartupRenderingGlScope.() -> T,
    ): T =
        glLane.executeCurrent(onCancellation = onCancellation) {
            checkOpenForStartupRenderingGlAccess()
            val ownedTarget = target as? ProjectionTarget
                ?: throw StartupRenderingGlAccessException(
                    reason = StartupRenderingGlAccessFailureReason.ProjectionTargetOwnerMismatch,
                    message = "Projection target is not owned by this ProjectionTargetOwner.",
                )
            ownedTarget.validateForStartupRenderingGlAccess(expectedOwner = this@ProjectionTargetOwner, expectedGeneration = generation)
            val retirementLane = startupRenderingRetirementLane()
            val glAccess = ScopedGlLaneAccess(this)
            val targetAccess = ScopedProjectionTargetGlAccess(target = ownedTarget, gl = glAccess)
            val access = ScopedStartupRenderingGlAccess(
                scopedGl = glAccess,
                scopedProjectionTarget = targetAccess,
                retirementLane = retirementLane,
                abandonment = this@ProjectionTargetOwner,
            )
            try {
                block(access)
            } finally {
                access.close()
            }
        }

    override suspend fun withCurrentProjectionTarget(
        target: ProjectionTargetHandle,
        generation: Long,
        block: ProjectionTargetGlScope.() -> Unit,
    ) {
        glLane.executeCurrent {
            checkOpen()
            val ownedTarget = target as? ProjectionTarget
                ?: error("Projection target is not owned by this ProjectionTargetOwner.")
            ownedTarget.validateForCurrentGlAccess(expectedOwner = this@ProjectionTargetOwner, expectedGeneration = generation)
            val access = ScopedProjectionTargetGlAccess(target = ownedTarget, gl = this)
            try {
                block(access)
            } finally {
                access.close()
            }
        }
    }

    override suspend fun installRuntimeFrameSignalSink(
        target: ProjectionTargetHandle,
        generation: Long,
        sink: RuntimeFrameSignalSink,
    ) {
        glLane.executeCurrent {
            checkOpen()
            val ownedTarget = target as? ProjectionTarget
                ?: error("Projection target is not owned by this ProjectionTargetOwner.")
            ownedTarget.validateForCurrentGlAccess(expectedOwner = this@ProjectionTargetOwner, expectedGeneration = generation)
            ownedTarget.installRuntimeFrameSignalSink(sink)
        }
    }

    override suspend fun clearRuntimeFrameSignalSink(
        target: ProjectionTargetHandle,
        generation: Long,
    ) {
        glLane.executeCurrent {
            checkOpen()
            val ownedTarget = target as? ProjectionTarget
                ?: error("Projection target is not owned by this ProjectionTargetOwner.")
            ownedTarget.validateForCurrentGlAccess(expectedOwner = this@ProjectionTargetOwner, expectedGeneration = generation)
            ownedTarget.clearRuntimeFrameSignalSink()
        }
    }

    override suspend fun <T> withCurrentRuntimeProjectionTarget(
        target: ProjectionTargetHandle,
        generation: Long,
        onCancellation: (T) -> Unit,
        block: RuntimeProjectionTargetGlScope.() -> T,
    ): T =
        glLane.executeCurrent(onCancellation = onCancellation) {
            checkOpen()
            val ownedTarget = target as? ProjectionTarget
                ?: error("Projection target is not owned by this ProjectionTargetOwner.")
            ownedTarget.validateForCurrentGlAccess(expectedOwner = this@ProjectionTargetOwner, expectedGeneration = generation)
            val glAccess = ScopedGlLaneAccess(this)
            val targetAccess = ScopedRuntimeProjectionTargetGlAccess(target = ownedTarget, scopedGl = glAccess)
            try {
                block(targetAccess)
            } finally {
                targetAccess.close()
                glAccess.close()
            }
        }

    private fun createTargetOnGlThread(
        gl: GlLaneScope,
        generation: Long,
        width: Int,
        height: Int,
        densityDpi: Int,
    ): ProjectionTarget {
        validateTargetSizeOnGlThread(gl, width, height)
        val textureId = createExternalOesTexture(gl)
        var surfaceTexture: SurfaceTexture? = null
        var surface: Surface? = null
        try {
            surfaceTexture = SurfaceTexture(textureId).apply {
                setDefaultBufferSize(width, height)
            }
            surface = Surface(surfaceTexture)
            check(surface.isValid) { "Projection target Surface is not valid." }
            return ProjectionTarget(
                generation = generation,
                width = width,
                height = height,
                densityDpi = densityDpi,
                androidSurface = surface,
                owner = this,
                surfaceTexture = surfaceTexture,
                textureId = textureId,
            )
        } catch (cause: Throwable) {
            surface?.release()
            surfaceTexture?.let {
                releaseSurfaceTextureAndTextureOnGlThread(gl = gl, surfaceTexture = it, textureId = textureId)
            } ?: deleteTextureOnGlThread(textureId)
            throw cause
        }
    }

    private fun createExternalOesTexture(gl: GlLaneScope): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        gl.checkGl("glGenTextures")
        val textureId = textures[0]
        check(textureId != 0) { "glGenTextures returned 0." }

        try {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            gl.checkGl("glBindTexture")
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
            gl.checkGl("configure external OES texture")
        } catch (cause: Throwable) {
            deleteTextureOnGlThread(textureId)
            throw cause
        }
        return textureId
    }

    private fun validateTargetSizeOnGlThread(gl: GlLaneScope, width: Int, height: Int) {
        val limits = gl.targetSizeLimits()
        check((width <= limits.maxWidth) && (height <= limits.maxHeight)) {
            "Projection target ${width}x$height exceeds GL target size limits ${limits.maxWidth}x${limits.maxHeight}."
        }
    }

    private fun closeTarget(target: ProjectionTarget, surface: Surface, surfaceTexture: SurfaceTexture, textureId: Int) {
        stateLock.withLock {
            if (!target.markClosedForOwner()) return
            if (!liveTargets.remove(target)) return
            activeReleases += 1
        }
        try {
            releaseTargetResources(surface, surfaceTexture, textureId)
        } finally {
            stateLock.withLock {
                activeReleases -= 1
                noActiveWork.signalAll()
            }
        }
    }

    private fun releaseTargetResources(surface: Surface, surfaceTexture: SurfaceTexture, textureId: Int) {
        val cleanupFailures = CleanupFailureCollector()
        if (!isGlLaneAbandonedForOwner()) {
            cleanupFailures.collect {
                glLane.executeCurrentIfCreatedBlocking {
                    clearSurfaceTextureFrameListenerOnGlThread(surfaceTexture)
                }
            }
        }
        cleanupFailures.collect { surface.release() }
        var releasedOnGlLane = false
        if (!isGlLaneAbandonedForOwner()) {
            cleanupFailures.collect {
                glLane.executeCurrentIfCreatedBlocking {
                    releaseSurfaceTextureAndTextureOnGlThread(
                        gl = this,
                        surfaceTexture = surfaceTexture,
                        textureId = textureId,
                    )
                    releasedOnGlLane = true
                }
            }
        }
        if (!releasedOnGlLane) {
            cleanupFailures.collect { surfaceTexture.release() }
        }
        cleanupFailures.throwIfAny()
    }

    private fun clearSurfaceTextureFrameListenerOnGlThread(surfaceTexture: SurfaceTexture) {
        runCatching { surfaceTexture.setOnFrameAvailableListener(null) }
    }

    private fun releaseSurfaceTextureAndTextureOnGlThread(
        gl: GlLaneScope,
        surfaceTexture: SurfaceTexture,
        textureId: Int,
    ) {
        runCatching { surfaceTexture.release() }
        deleteTextureOnGlThread(textureId)
        gl.checkGl("release projection target texture")
    }

    private fun deleteTextureOnGlThread(textureId: Int) {
        if (textureId == 0) return
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
    }

    private fun isGlLaneAbandonedForOwner(): Boolean =
        stateLock.withLock { isAbandoned } || ((glLane as? GlLaneAbandonment)?.isGlLaneAbandoned == true)

    private fun startupRenderingRetirementLane(): GlResourceRetirementLane =
        glLane as? GlResourceRetirementLane
            ?: error("Projection target GL lane does not provide GL resource retirement.")

    private fun checkOpen() {
        stateLock.withLock {
            checkOpenLocked()
        }
    }

    private fun checkOpenForStartupRenderingGlAccess() {
        stateLock.withLock {
            if (isClosed || isAbandoned) {
                throw StartupRenderingGlAccessException(
                    reason = StartupRenderingGlAccessFailureReason.ProjectionTargetOwnerClosed,
                    message = "ProjectionTargetOwner is closed.",
                )
            }
        }
    }

    private fun checkOpenLocked() {
        check(!isClosed && !isAbandoned) { "ProjectionTargetOwner is closed." }
    }

    internal class ProjectionTarget internal constructor(
        override val generation: Long,
        override val width: Int,
        override val height: Int,
        override val densityDpi: Int,
        private val androidSurface: Surface,
        private val owner: ProjectionTargetOwner,
        private val surfaceTexture: SurfaceTexture,
        private val textureId: Int,
    ) : ProjectionTargetHandle {
        private val isClosed = AtomicBoolean()
        private val runtimeTargetIdentity = RuntimeProjectionTargetInstanceIdentity()
        override val surface: ProjectionSurfaceHandle = AndroidProjectionSurfaceHandle(androidSurface)

        override fun close() {
            owner.closeTarget(target = this, surface = androidSurface, surfaceTexture = surfaceTexture, textureId = textureId)
        }

        internal fun markClosedForOwner(): Boolean = isClosed.compareAndSet(false, true)

        internal fun releaseOwnedResources() {
            owner.releaseTargetResources(surface = androidSurface, surfaceTexture = surfaceTexture, textureId = textureId)
        }

        internal fun validateForCurrentGlAccess(expectedOwner: ProjectionTargetOwner, expectedGeneration: Long) {
            check(owner === expectedOwner) { "Projection target is owned by a different ProjectionTargetOwner." }
            check(generation == expectedGeneration) {
                "Projection target generation mismatch. Expected $expectedGeneration, was $generation."
            }
            check(!isClosed.get()) { "Projection target generation $generation is closed." }
        }

        internal fun validateForStartupRenderingGlAccess(expectedOwner: ProjectionTargetOwner, expectedGeneration: Long) {
            if (owner !== expectedOwner) {
                throw StartupRenderingGlAccessException(
                    reason = StartupRenderingGlAccessFailureReason.ProjectionTargetOwnerMismatch,
                    message = "Projection target is owned by a different ProjectionTargetOwner.",
                )
            }
            if (generation != expectedGeneration) {
                throw StartupRenderingGlAccessException(
                    reason = StartupRenderingGlAccessFailureReason.ProjectionTargetGenerationMismatch,
                    message = "Projection target generation mismatch. Expected $expectedGeneration, was $generation.",
                )
            }
            if (isClosed.get()) {
                throw StartupRenderingGlAccessException(
                    reason = StartupRenderingGlAccessFailureReason.ProjectionTargetClosed,
                    message = "Projection target generation $generation is closed.",
                )
            }
        }

        internal fun validateExternalOesTextureOnCurrentGlThread(gl: GlLaneScope) {
            gl.checkCurrentContext("Projection target external OES validation")
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            gl.checkGl("validate projection target external OES texture")
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
            gl.checkGl("unbind projection target external OES texture")
        }

        internal fun installRuntimeFrameSignalSink(sink: RuntimeFrameSignalSink) {
            // SurfaceTexture callbacks are enqueue-only signals; frame consumption stays inside
            // RuntimeProjectionTargetGlScope on the owning GL lane after the public commit.
            surfaceTexture.setOnFrameAvailableListener(
                RuntimeProjectionTargetFrameAvailableListener(
                    generation = generation,
                    sink = sink,
                ),
            )
        }

        internal fun clearRuntimeFrameSignalSink() {
            surfaceTexture.setOnFrameAvailableListener(null)
        }

        internal fun updateTexImageOnCurrentGlThread(gl: GlLaneScope) {
            gl.checkCurrentContext("Runtime projection target frame update")
            surfaceTexture.updateTexImage()
        }

        internal fun getTransformMatrixOnCurrentGlThread(gl: GlLaneScope, destination: FloatArray) {
            require(destination.size >= RUNTIME_OES_MATRIX_ELEMENT_COUNT) {
                "OES transform matrix destination must contain at least 16 values."
            }
            gl.checkCurrentContext("Runtime projection target transform matrix")
            surfaceTexture.getTransformMatrix(destination)
        }

        internal fun timestampNanosOnCurrentGlThread(gl: GlLaneScope): Long {
            gl.checkCurrentContext("Runtime projection target timestamp")
            return surfaceTexture.timestamp
        }

        internal fun externalOesTexture(): RuntimeExternalOesTexture =
            RuntimeExternalOesTexture(textureId = textureId)

        internal fun runtimeProjectionTargetIdentity(): RuntimeProjectionTargetIdentity =
            RuntimeProjectionTargetIdentity(
                ownerIdentity = owner.runtimeOwnerIdentity,
                targetIdentity = runtimeTargetIdentity,
                generation = generation,
                externalOesTexture = externalOesTexture(),
            )
    }

    private class ScopedProjectionTargetGlAccess(
        private val target: ProjectionTarget,
        private val gl: GlLaneScope,
    ) : ProjectionTargetGlScope, AutoCloseable {
        private var isActive = true

        override val generation: Long
            get() {
                checkActive()
                return target.generation
            }

        override val width: Int
            get() {
                checkActive()
                return target.width
            }

        override val height: Int
            get() {
                checkActive()
                return target.height
            }

        override val densityDpi: Int
            get() {
                checkActive()
                return target.densityDpi
            }

        override fun validateExternalOesTexture() {
            checkActive()
            target.validateExternalOesTextureOnCurrentGlThread(gl)
        }

        override fun close() {
            isActive = false
        }

        private fun checkActive() {
            check(isActive) { "Projection target GL access is no longer active." }
            gl.checkCurrentContext("Projection target GL access")
        }
    }

    private class ScopedStartupRenderingGlAccess(
        private val scopedGl: ScopedGlLaneAccess,
        private val scopedProjectionTarget: ScopedProjectionTargetGlAccess,
        override val retirementLane: GlResourceRetirementLane,
        override val abandonment: GlLaneAbandonment,
    ) : StartupRenderingGlScope, AutoCloseable {
        private var isActive = true

        override val gl: GlLaneScope
            get() {
                checkActive()
                return scopedGl
            }

        override val projectionTarget: ProjectionTargetGlScope
            get() {
                checkActive()
                return scopedProjectionTarget
            }

        override fun close() {
            isActive = false
            scopedProjectionTarget.close()
            scopedGl.close()
        }

        private fun checkActive() {
            check(isActive) { "Startup rendering GL access is no longer active." }
        }
    }

    private class ScopedRuntimeProjectionTargetGlAccess(
        private val target: ProjectionTarget,
        private val scopedGl: GlLaneScope,
    ) : RuntimeProjectionTargetGlScope, AutoCloseable {
        private var isActive = true

        override val gl: GlLaneScope
            get() {
                checkActive()
                return scopedGl
            }

        override val generation: Long
            get() {
                checkActive()
                return target.generation
            }

        override val width: Int
            get() {
                checkActive()
                return target.width
            }

        override val height: Int
            get() {
                checkActive()
                return target.height
            }

        override val densityDpi: Int
            get() {
                checkActive()
                return target.densityDpi
            }

        override val projectionTargetIdentity: RuntimeProjectionTargetIdentity
            get() {
                checkActive()
                return target.runtimeProjectionTargetIdentity()
            }

        override val externalOesTexture: RuntimeExternalOesTexture
            get() {
                checkActive()
                return target.externalOesTexture()
            }

        override fun updateTexImage() {
            checkActive()
            target.updateTexImageOnCurrentGlThread(scopedGl)
        }

        override fun getTransformMatrix(destination: FloatArray) {
            checkActive()
            target.getTransformMatrixOnCurrentGlThread(gl = scopedGl, destination = destination)
        }

        override fun timestampNanos(): Long {
            checkActive()
            return target.timestampNanosOnCurrentGlThread(scopedGl)
        }

        override fun close() {
            isActive = false
        }

        private fun checkActive() {
            check(isActive) { "Runtime projection target GL access is no longer active." }
            scopedGl.checkCurrentContext("Runtime projection target GL access")
        }
    }

    private class ScopedGlLaneAccess(
        private val gl: GlLaneScope,
    ) : GlLaneScope, AutoCloseable {
        private var isActive = true

        override fun targetSizeLimits(): ProjectionTargetSizeLimits {
            checkActive()
            return gl.targetSizeLimits()
        }

        override fun checkCurrentContext(operation: String) {
            checkActive()
            gl.checkCurrentContext(operation)
        }

        override fun checkGl(operation: String) {
            checkActive()
            gl.checkGl(operation)
        }

        override fun close() {
            isActive = false
        }

        private fun checkActive() {
            check(isActive) { "Startup rendering GL access is no longer active." }
        }
    }
}

/**
 * Owner-mediated GL access to a supplied projection target handle and expected generation.
 *
 * Access runs only on the owning GL lane and validates the supplied handle's owner, generation,
 * and closed state before exposing OES texture validation to rendering preparation code. This
 * capability is for target validation/readiness; it does not consume frames or read pixels.
 */
internal interface ProjectionTargetGlCapability {
    suspend fun withCurrentProjectionTarget(
        target: ProjectionTargetHandle,
        generation: Long,
        block: ProjectionTargetGlScope.() -> Unit,
    )
}

internal interface ProjectionTargetOwnerAbandonment {
    fun abandonGlLane()
}

internal interface ProjectionTargetGlScope {
    val generation: Long
    val width: Int
    val height: Int
    val densityDpi: Int

    fun validateExternalOesTexture()
}

private const val RUNTIME_OES_MATRIX_ELEMENT_COUNT: Int = 16
