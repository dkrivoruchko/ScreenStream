/*
 *  Copyright 2016 The WebRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package org.webrtc;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.opengl.GLException;
import android.view.Surface;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Implements VideoSink by displaying the video stream on an EGL Surface. This class is intended to
 * be used as a helper class for rendering on SurfaceViews and TextureViews.
 */
public class EglRenderer implements VideoSink {
  private static final String TAG = "EglRenderer";
  private static final UUID EMPTY_UUID = new UUID(0L, 0L); // All zeros
  private static final long LOG_INTERVAL_SEC = 4;

  public interface FrameListener { void onFrame(Bitmap frame); }

  /**
   * Can be implemented by the clients who want to know exactly when a render happens.
   */
  public interface RenderListener {
    /** Fired when swapBuffers happens. */
    void onRender(long timestampNs);
  }

  /** Callback for clients to be notified about errors encountered during rendering. */
  public static interface ErrorCallback {
    /** Called if GLES20.GL_OUT_OF_MEMORY is encountered during rendering. */
    void onGlOutOfMemory();
  }

  private static class FrameListenerAndParams {
    public final FrameListener listener;
    public final float scale;
    public final RendererCommon.GlDrawer drawer;
    public final boolean applyFpsReduction;

    public FrameListenerAndParams(FrameListener listener, float scale,
        RendererCommon.GlDrawer drawer, boolean applyFpsReduction) {
      this.listener = listener;
      this.scale = scale;
      this.drawer = drawer;
      this.applyFpsReduction = applyFpsReduction;
    }
  }

  private class EglSurfaceCreation implements Runnable {
    private Object surface;

    // TODO(bugs.webrtc.org/8491): Remove NoSynchronizedMethodCheck suppression.
    @SuppressWarnings("NoSynchronizedMethodCheck")
    public synchronized void setSurface(Object surface) {
      this.surface = surface;
    }

    @Override
    // TODO(bugs.webrtc.org/8491): Remove NoSynchronizedMethodCheck suppression.
    @SuppressWarnings("NoSynchronizedMethodCheck")
    public synchronized void run() {
      if (surface != null && eglBase != null && !eglBase.hasSurface()) {
        if (surface instanceof Surface) {
          eglBase.createSurface((Surface) surface);
        } else if (surface instanceof SurfaceTexture) {
          eglBase.createSurface((SurfaceTexture) surface);
        } else {
          throw new IllegalStateException("Invalid surface: " + surface);
        }
        eglBase.makeCurrent();
        // Necessary for YUV frames with odd width.
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
      }
    }
  }

  protected final String name;

  // An id to uniquely identify the renderer, used for when we're scheduling
  // frames for render.
  private UUID id = EMPTY_UUID;

  // `eglThread` is used for rendering, and is synchronized on `threadLock`.
  private final Object threadLock = new Object();
  @GuardedBy("threadLock") @Nullable private EglThread eglThread;

  private final Runnable eglExceptionCallback = new Runnable() {
    @Override
    public void run() {
      synchronized (threadLock) {
        eglThread = null;
      }
    }
  };

  private final ArrayList<FrameListenerAndParams> frameListeners = new ArrayList<>();

  private final ArrayList<RenderListener> renderListeners = new ArrayList<>();

  private volatile ErrorCallback errorCallback;

  // Variables for fps reduction.
  private final Object fpsReductionLock = new Object();
  // Time for when next frame should be rendered.
  private long nextFrameTimeNs;
  // Minimum duration between frames when fps reduction is active, or -1 if video is completely
  // paused.
  private long minRenderPeriodNs;

  // EGL and GL resources for drawing YUV/OES textures. After initialization, these are only
  // accessed from the render thread.
  @Nullable private EglBase eglBase;
  private final VideoFrameDrawer frameDrawer;
  @Nullable private RendererCommon.GlDrawer drawer;
  private boolean usePresentationTimeStamp;
  private final Matrix drawMatrix = new Matrix();

  // Pending frame to render. Serves as a queue with size 1. Synchronized on `frameLock`.
  private final Object frameLock = new Object();
  @Nullable private VideoFrame pendingFrame;

  // These variables are synchronized on `layoutLock`.
  private final Object layoutLock = new Object();
  private float layoutAspectRatio;
  // If true, mirrors the video stream horizontally.
  private boolean mirrorHorizontally;
  // If true, mirrors the video stream vertically.
  private boolean mirrorVertically;

  // These variables are synchronized on `statisticsLock`.
  private final Object statisticsLock = new Object();
  // Total number of video frames received in renderFrame() call.
  private int framesReceived;
  // Number of video frames dropped by renderFrame() because previous frame has not been rendered
  // yet.
  private int framesDropped;
  // Number of rendered video frames.
  private int framesRendered;
  // Start time for counting these statistics, or 0 if we haven't started measuring yet.
  private long statisticsStartTimeNs;
  // Time in ns spent in renderFrameOnRenderThread() function.
  private long renderTimeNs;
  // Time in ns spent by the render thread in the swapBuffers() function.
  private long renderSwapBufferTimeNs;

  // Used for bitmap capturing.
  private final GlTextureFrameBuffer bitmapTextureFramebuffer =
      new GlTextureFrameBuffer(GLES20.GL_RGBA);

  private final Runnable logStatisticsRunnable = new Runnable() {
    @Override
    public void run() {
      logStatistics();
      synchronized (threadLock) {
        if (eglThread != null) {
          eglThread.getHandler().removeCallbacks(logStatisticsRunnable);
          eglThread.getHandler().postDelayed(
              logStatisticsRunnable, TimeUnit.SECONDS.toMillis(LOG_INTERVAL_SEC));
        }
      }
    }
  };

  private final EglSurfaceCreation eglSurfaceCreationRunnable = new EglSurfaceCreation();

  /**
   * Standard constructor. The name will be included when logging. In order to render something,
   * you must first call init() and createEglSurface.
   */
  public EglRenderer(String name) {
    this(name, new VideoFrameDrawer());
  }

  public EglRenderer(String name, VideoFrameDrawer videoFrameDrawer) {
    this.name = name;
    this.frameDrawer = videoFrameDrawer;
  }

  public void init(
      EglThread eglThread, RendererCommon.GlDrawer drawer, boolean usePresentationTimeStamp) {
    synchronized (threadLock) {
      if (this.eglThread != null) {
        throw new IllegalStateException(name + "Already initialized");
      }

      logD("Initializing EglRenderer");
      this.eglThread = eglThread;
      this.drawer = drawer;
      this.usePresentationTimeStamp = usePresentationTimeStamp;

      eglThread.addExceptionCallback(eglExceptionCallback);

      eglBase = eglThread.createEglBaseWithSharedConnection();
      eglThread.getHandler().post(eglSurfaceCreationRunnable);

      final long currentTimeNs = System.nanoTime();
      resetStatistics(currentTimeNs);

      eglThread.getHandler().postDelayed(
          logStatisticsRunnable, TimeUnit.SECONDS.toMillis(LOG_INTERVAL_SEC));
    }
  }

  /**
   * Intializes this class with the given parameters.
   *
   * The new parameter here, `overwritePendingFrames` overwrites instead of
   * queueing frames when passing them to the synchronized renderer.
   */
  public void init(
      EglThread eglThread,
      RendererCommon.GlDrawer drawer,
      boolean usePresentationTimeStamp,
      boolean overwritePendingFrames) {
      if (overwritePendingFrames) {
        id = UUID.randomUUID();
      }
      init(eglThread, drawer, usePresentationTimeStamp);
  }

  /**
   * Initialize this class, sharing resources with `sharedContext`. The custom `drawer` will be used
   * for drawing frames on the EGLSurface. This class is responsible for calling release() on
   * `drawer`. It is allowed to call init() to reinitialize the renderer after a previous
   * init()/release() cycle. If usePresentationTimeStamp is true, eglPresentationTimeANDROID will be
   * set with the frame timestamps, which specifies desired presentation time and might be useful
   * for e.g. syncing audio and video.
   */
  public void init(@Nullable final EglBase.Context sharedContext, final int[] configAttributes,
      RendererCommon.GlDrawer drawer, boolean usePresentationTimeStamp) {
    EglThread thread =
        EglThread.create(/* releaseMonitor= */ null, sharedContext, configAttributes);
    init(thread, drawer, usePresentationTimeStamp);
  }

  /**
   * Same as above with usePresentationTimeStamp set to false.
   *
   * @see #init(EglBase.Context, int[], RendererCommon.GlDrawer, boolean)
   */
  public void init(@Nullable final EglBase.Context sharedContext, final int[] configAttributes,
      RendererCommon.GlDrawer drawer) {
    init(sharedContext, configAttributes, drawer, /* usePresentationTimeStamp= */ false);
  }

  public void createEglSurface(Surface surface) {
    createEglSurfaceInternal(surface);
  }

  public void createEglSurface(SurfaceTexture surfaceTexture) {
    createEglSurfaceInternal(surfaceTexture);
  }

  private void createEglSurfaceInternal(Object surface) {
    eglSurfaceCreationRunnable.setSurface(surface);
    postToRenderThread(eglSurfaceCreationRunnable);
  }

  /**
   * Block until any pending frame is returned and all GL resources released, even if an interrupt
   * occurs. If an interrupt occurs during release(), the interrupt flag will be set. This function
   * should be called before the Activity is destroyed and the EGLContext is still valid. If you
   * don't call this function, the GL resources might leak.
   */
  public void release() {
    logD("Releasing.");
    final CountDownLatch eglCleanupBarrier = new CountDownLatch(1);
    synchronized (threadLock) {
      if (eglThread == null) {
        logD("Already released");
        return;
      }
      eglThread.getHandler().removeCallbacks(logStatisticsRunnable);
      eglThread.removeExceptionCallback(eglExceptionCallback);

      // Release EGL and GL resources on render thread.
      eglThread.getHandler().postAtFrontOfQueue(() -> {
        // Detach current shader program.
        synchronized (EglBase.lock) {
          GLES20.glUseProgram(/* program= */ 0);
        }
        if (drawer != null) {
          drawer.release();
          drawer = null;
        }
        frameDrawer.release();
        bitmapTextureFramebuffer.release();

        if (eglBase != null) {
          logD("eglBase detach and release.");
          eglBase.detachCurrent();
          eglBase.release();
          eglBase = null;
        }

        renderListeners.clear();
        frameListeners.clear();
        eglCleanupBarrier.countDown();
      });

      // Don't accept any more frames or messages to the render thread.
      eglThread.release();
      eglThread = null;
    }
    // Make sure the EGL/GL cleanup posted above is executed.
    ThreadUtils.awaitUninterruptibly(eglCleanupBarrier);
    synchronized (frameLock) {
      if (pendingFrame != null) {
        pendingFrame.release();
        pendingFrame = null;
      }
    }
    logD("Releasing done.");
  }

  /**
   * Reset the statistics logged in logStatistics().
   */
  private void resetStatistics(long currentTimeNs) {
    synchronized (statisticsLock) {
      statisticsStartTimeNs = currentTimeNs;
      framesReceived = 0;
      framesDropped = 0;
      framesRendered = 0;
      renderTimeNs = 0;
      renderSwapBufferTimeNs = 0;
    }
  }

  public void printStackTrace() {
    synchronized (threadLock) {
      final Thread renderThread =
          (eglThread == null) ? null : eglThread.getHandler().getLooper().getThread();
      if (renderThread != null) {
        final StackTraceElement[] renderStackTrace = renderThread.getStackTrace();
        if (renderStackTrace.length > 0) {
          logW("EglRenderer stack trace:");
          for (StackTraceElement traceElem : renderStackTrace) {
            logW(traceElem.toString());
          }
        }
      }
    }
  }

  /**
   * Set if the video stream should be mirrored horizontally or not.
   */
  public void setMirror(final boolean mirror) {
    synchronized (layoutLock) {
      this.mirrorHorizontally = mirror;
    }
  }

  /**
   * Set if the video stream should be mirrored vertically or not.
   */
  public void setMirrorVertically(final boolean mirrorVertically) {
    synchronized (layoutLock) {
      this.mirrorVertically = mirrorVertically;
    }
  }

  /**
   * Set layout aspect ratio. This is used to crop frames when rendering to avoid stretched video.
   * Set this to 0 to disable cropping.
   */
  public void setLayoutAspectRatio(float layoutAspectRatio) {
    synchronized (layoutLock) {
      this.layoutAspectRatio = layoutAspectRatio;
    }
  }

  /**
   * Limit render framerate.
   *
   * @param fps Limit render framerate to this value, or use Float.POSITIVE_INFINITY to disable fps
   *            reduction.
   */
  public void setFpsReduction(float fps) {
    synchronized (fpsReductionLock) {
      final long previousRenderPeriodNs = minRenderPeriodNs;
      if (fps <= 0) {
        minRenderPeriodNs = Long.MAX_VALUE;
      } else {
        minRenderPeriodNs = (long) (TimeUnit.SECONDS.toNanos(1) / fps);
      }
      if (minRenderPeriodNs != previousRenderPeriodNs) {
        // Fps reduction changed - reset frame time.
        nextFrameTimeNs = System.nanoTime();
      }
    }
  }

  public void disableFpsReduction() {
    setFpsReduction(Float.POSITIVE_INFINITY /* fps */);
  }

  public void pauseVideo() {
    setFpsReduction(0 /* fps */);
  }

  /**
   * Register a callback to be invoked when a new video frame has been received. This version uses
   * the drawer of the EglRenderer that was passed in init.
   *
   * @param listener The callback to be invoked. The callback will be invoked on the render thread.
   *                 It should be lightweight and must not call removeFrameListener.
   * @param scale    The scale of the Bitmap passed to the callback, or 0 if no Bitmap is
   *                 required.
   */
  public void addFrameListener(final FrameListener listener, final float scale) {
    addFrameListener(listener, scale, null, false /* applyFpsReduction */);
  }

  /**
   * Register a callback to be invoked when a new video frame has been received.
   *
   * @param listener The callback to be invoked. The callback will be invoked on the render thread.
   *                 It should be lightweight and must not call removeFrameListener.
   * @param scale    The scale of the Bitmap passed to the callback, or 0 if no Bitmap is
   *                 required.
   * @param drawerParam   Custom drawer to use for this frame listener or null to use the default.
   */
  public void addFrameListener(
      final FrameListener listener, final float scale, final RendererCommon.GlDrawer drawerParam) {
    addFrameListener(listener, scale, drawerParam, false /* applyFpsReduction */);
  }

  /**
   * Register a callback to be invoked when a new video frame has been received.
   *
   * @param listener The callback to be invoked. The callback will be invoked on the render thread.
   *                 It should be lightweight and must not call removeFrameListener.
   * @param scale    The scale of the Bitmap passed to the callback, or 0 if no Bitmap is
   *                 required.
   * @param drawerParam   Custom drawer to use for this frame listener or null to use the default.
   * @param applyFpsReduction This callback will not be called for frames that have been dropped by
   *                          FPS reduction.
   */
  public void addFrameListener(final FrameListener listener, final float scale,
      @Nullable final RendererCommon.GlDrawer drawerParam, final boolean applyFpsReduction) {
    postToRenderThread(() -> {
      final RendererCommon.GlDrawer listenerDrawer = drawerParam == null ? drawer : drawerParam;
      frameListeners.add(
          new FrameListenerAndParams(listener, scale, listenerDrawer, applyFpsReduction));
    });
  }

  /**
   * Register a callback to be invoked when a new video frame has been rendered.
   *
   * @param listener The callback to be invoked. The callback will be invoked on the render thread.
   *                 It should be lightweight and must not call removeRenderListener.
   */
  public void addRenderListener(final RenderListener listener) {
    renderListeners.add(listener);
  }

  /**
   * Remove any pending callback that was added with addFrameListener. If the callback is not in
   * the queue, nothing happens. It is ensured that callback won't be called after this method
   * returns.
   *
   * @param listener The callback to remove.
   */
  public void removeFrameListener(final FrameListener listener) {
    final CountDownLatch latch = new CountDownLatch(1);
    synchronized (threadLock) {
      if (eglThread == null) {
        return;
      }
      if (Thread.currentThread() == eglThread.getHandler().getLooper().getThread()) {
        throw new RuntimeException("removeFrameListener must not be called on the render thread.");
      }
      postToRenderThread(() -> {
        latch.countDown();
        final Iterator<FrameListenerAndParams> iter = frameListeners.iterator();
        while (iter.hasNext()) {
          if (iter.next().listener == listener) {
            iter.remove();
          }
        }
      });
    }
    ThreadUtils.awaitUninterruptibly(latch);
  }

  /**
   * Remove any pending callback that was added with addRenderListener. If the callback is not in
   * the queue, nothing happens. It is ensured that callback won't be called after this method
   * returns.
   *
   * @param listener The callback to remove.
   */
  public void removeRenderListener(final RenderListener listener) {
    final CountDownLatch latch = new CountDownLatch(1);
    synchronized (threadLock) {
      if (eglThread == null) {
        return;
      }
      if (Thread.currentThread() == eglThread.getHandler().getLooper().getThread()) {
        throw new RuntimeException("removeRenderListener must not be called on the render thread.");
      }
      postToRenderThread(
          () -> {
            latch.countDown();
            final Iterator<RenderListener> iter = renderListeners.iterator();
            while (iter.hasNext()) {
              if (iter.next() == listener) {
                iter.remove();
              }
            }
          });
    }
    ThreadUtils.awaitUninterruptibly(latch);
  }

  /** Can be set in order to be notified about errors encountered during rendering. */
  public void setErrorCallback(ErrorCallback errorCallback) {
    this.errorCallback = errorCallback;
  }

  // VideoSink interface.
  @Override
  public void onFrame(VideoFrame frame) {
    synchronized (statisticsLock) {
      ++framesReceived;
    }
    final boolean dropOldFrame;
    synchronized (threadLock) {
      if (eglThread == null) {
        logD("Dropping frame - Not initialized or already released.");
        return;
      }
      synchronized (frameLock) {
        dropOldFrame = (pendingFrame != null);
        if (dropOldFrame) {
          pendingFrame.release();
        }
        pendingFrame = frame;
        pendingFrame.retain();
        eglThread.getHandler().post(this::renderFrameOnRenderThread);
      }
    }
    if (dropOldFrame) {
      synchronized (statisticsLock) {
        ++framesDropped;
      }
    }
  }

  /**
   * Release EGL surface. This function will block until the EGL surface is released.
   */
  public void releaseEglSurface(final Runnable completionCallback) {
    // Ensure that the render thread is no longer touching the Surface before returning from this
    // function.
    eglSurfaceCreationRunnable.setSurface(null /* surface */);
    synchronized (threadLock) {
      if (eglThread != null) {
        eglThread.getHandler().removeCallbacks(eglSurfaceCreationRunnable);
        eglThread.getHandler().postAtFrontOfQueue(() -> {
          if (eglBase != null) {
            eglBase.detachCurrent();
            eglBase.releaseSurface();
          }
          completionCallback.run();
        });
        return;
      }
    }
    completionCallback.run();
  }

  /**
   * Private helper function to post tasks safely.
   */
  private void postToRenderThread(Runnable runnable) {
    synchronized (threadLock) {
      if (eglThread != null) {
        eglThread.getHandler().post(runnable);
      }
    }
  }

  private void clearSurfaceOnRenderThread(float r, float g, float b, float a) {
    if (eglBase != null && eglBase.hasSurface()) {
      logD("clearSurface");
      eglBase.makeCurrent();
      GLES20.glClearColor(r, g, b, a);
      GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
      eglBase.swapBuffers();
    }
  }

  /**
   * Post a task to clear the surface to a transparent uniform color.
   */
  public void clearImage() {
    clearImage(0 /* red */, 0 /* green */, 0 /* blue */, 0 /* alpha */);
  }

  /**
   * Post a task to clear the surface to a specific color.
   */
  public void clearImage(final float r, final float g, final float b, final float a) {
    synchronized (threadLock) {
      if (eglThread == null) {
        return;
      }
      eglThread.getHandler().postAtFrontOfQueue(() -> clearSurfaceOnRenderThread(r, g, b, a));
    }
  }

  private void swapBuffersOnRenderThread(final VideoFrame frame, long swapBuffersStartTimeNs) {
    synchronized (threadLock) {
      if (eglThread == null) {
        return;
      }
      EglThread.RenderUpdate renderUpdate =
          runsInline -> {
            if (!runsInline) {
              if (eglBase == null || !eglBase.hasSurface()) {
                return;
              }
              eglBase.makeCurrent();
            }

            if (usePresentationTimeStamp) {
              eglBase.swapBuffers(frame.getTimestampNs());
            } else {
              eglBase.swapBuffers();
            }

            for (var listener : renderListeners) {
              listener.onRender(System.nanoTime());
            }

            synchronized (statisticsLock) {
              renderSwapBufferTimeNs += (System.nanoTime() - swapBuffersStartTimeNs);
            }
          };
      if (id.equals(EMPTY_UUID)) {
        eglThread.scheduleRenderUpdate(renderUpdate);
      } else {
        eglThread.scheduleRenderUpdate(id, renderUpdate);
      }
    }
  }

  /**
   * Renders and releases `pendingFrame`.
   */
  private void renderFrameOnRenderThread() {
    // Fetch and render `pendingFrame`.
    final VideoFrame frame;
    synchronized (frameLock) {
      if (pendingFrame == null) {
        return;
      }
      frame = pendingFrame;
      pendingFrame = null;
    }
    if (eglBase == null || !eglBase.hasSurface()) {
      logD("Dropping frame - No surface");
      frame.release();
      return;
    }
    try {
      eglBase.makeCurrent();
    } catch (GLException e) {
      logE("Error while eglMakeCurrent", e);
      frame.release();
      return;
    }

    // Check if fps reduction is active.
    final boolean shouldRenderFrame;
    synchronized (fpsReductionLock) {
      if (minRenderPeriodNs == Long.MAX_VALUE) {
        // Rendering is paused.
        shouldRenderFrame = false;
      } else if (minRenderPeriodNs <= 0) {
        // FPS reduction is disabled.
        shouldRenderFrame = true;
      } else {
        final long currentTimeNs = System.nanoTime();
        if (currentTimeNs < nextFrameTimeNs) {
          logD("Skipping frame rendering - fps reduction is active.");
          shouldRenderFrame = false;
        } else {
          nextFrameTimeNs += minRenderPeriodNs;
          // The time for the next frame should always be in the future.
          nextFrameTimeNs = Math.max(nextFrameTimeNs, currentTimeNs);
          shouldRenderFrame = true;
        }
      }
    }

    final long startTimeNs = System.nanoTime();

    final float frameAspectRatio = frame.getRotatedWidth() / (float) frame.getRotatedHeight();
    final float drawnAspectRatio;
    synchronized (layoutLock) {
      drawnAspectRatio = layoutAspectRatio != 0f ? layoutAspectRatio : frameAspectRatio;
    }

    final float scaleX;
    final float scaleY;

    if (frameAspectRatio > drawnAspectRatio) {
      scaleX = drawnAspectRatio / frameAspectRatio;
      scaleY = 1f;
    } else {
      scaleX = 1f;
      scaleY = frameAspectRatio / drawnAspectRatio;
    }

    drawMatrix.reset();
    drawMatrix.preTranslate(0.5f, 0.5f);
    drawMatrix.preScale(mirrorHorizontally ? -1f : 1f, mirrorVertically ? -1f : 1f);
    drawMatrix.preScale(scaleX, scaleY);
    drawMatrix.preTranslate(-0.5f, -0.5f);

    try {
      if (shouldRenderFrame) {
        GLES20.glClearColor(0 /* red */, 0 /* green */, 0 /* blue */, 0 /* alpha */);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        frameDrawer.drawFrame(frame, drawer, drawMatrix, 0 /* viewportX */, 0 /* viewportY */,
            eglBase.surfaceWidth(), eglBase.surfaceHeight());

        final long swapBuffersStartTimeNs = System.nanoTime();
        swapBuffersOnRenderThread(frame, swapBuffersStartTimeNs);

        synchronized (statisticsLock) {
          ++framesRendered;
          renderTimeNs += (swapBuffersStartTimeNs - startTimeNs);
        }
      }

      notifyCallbacks(frame, shouldRenderFrame);
    } catch (GlUtil.GlOutOfMemoryException e) {
      logE("Error while drawing frame", e);
      final ErrorCallback errorCallback = this.errorCallback;
      if (errorCallback != null) {
        errorCallback.onGlOutOfMemory();
      }
      // Attempt to free up some resources.
      drawer.release();
      frameDrawer.release();
      bitmapTextureFramebuffer.release();
      // Continue here on purpose and retry again for next frame. In worst case, this is a
      // continuous problem and no more frames will be drawn.
    } finally {
      frame.release();
    }
  }

  private void notifyCallbacks(VideoFrame frame, boolean wasRendered) {
    if (frameListeners.isEmpty())
      return;

    drawMatrix.reset();
    drawMatrix.preTranslate(0.5f, 0.5f);
    drawMatrix.preScale(mirrorHorizontally ? -1f : 1f, mirrorVertically ? -1f : 1f);
    drawMatrix.preScale(1f, -1f); // We want the output to be upside down for Bitmap.
    drawMatrix.preTranslate(-0.5f, -0.5f);

    Iterator<FrameListenerAndParams> it = frameListeners.iterator();
    while (it.hasNext()) {
      FrameListenerAndParams listenerAndParams = it.next();
      if (!wasRendered && listenerAndParams.applyFpsReduction) {
        continue;
      }
      it.remove();

      final int scaledWidth = (int) (listenerAndParams.scale * frame.getRotatedWidth());
      final int scaledHeight = (int) (listenerAndParams.scale * frame.getRotatedHeight());

      if (scaledWidth == 0 || scaledHeight == 0) {
        listenerAndParams.listener.onFrame(null);
        continue;
      }

      bitmapTextureFramebuffer.setSize(scaledWidth, scaledHeight);

      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, bitmapTextureFramebuffer.getFrameBufferId());
      GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
          GLES20.GL_TEXTURE_2D, bitmapTextureFramebuffer.getTextureId(), 0);

      GLES20.glClearColor(0 /* red */, 0 /* green */, 0 /* blue */, 0 /* alpha */);
      GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
      frameDrawer.drawFrame(frame, listenerAndParams.drawer, drawMatrix, 0 /* viewportX */,
          0 /* viewportY */, scaledWidth, scaledHeight);

      final ByteBuffer bitmapBuffer = ByteBuffer.allocateDirect(scaledWidth * scaledHeight * 4);
      GLES20.glViewport(0, 0, scaledWidth, scaledHeight);
      GLES20.glReadPixels(
          0, 0, scaledWidth, scaledHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bitmapBuffer);

      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
      GlUtil.checkNoGLES2Error("EglRenderer.notifyCallbacks");

      final Bitmap bitmap = Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888);
      bitmap.copyPixelsFromBuffer(bitmapBuffer);
      listenerAndParams.listener.onFrame(bitmap);
    }
  }

  private String averageTimeAsString(long sumTimeNs, int count) {
    return (count <= 0) ? "NA" : TimeUnit.NANOSECONDS.toMicros(sumTimeNs / count) + " us";
  }

  private void logStatistics() {
    final DecimalFormat fpsFormat = new DecimalFormat("#.0");
    final long currentTimeNs = System.nanoTime();
    synchronized (statisticsLock) {
      final long elapsedTimeNs = currentTimeNs - statisticsStartTimeNs;
      if (elapsedTimeNs <= 0 || (minRenderPeriodNs == Long.MAX_VALUE && framesReceived == 0)) {
        return;
      }
      final float renderFps = framesRendered * TimeUnit.SECONDS.toNanos(1) / (float) elapsedTimeNs;
      logD("Duration: " + TimeUnit.NANOSECONDS.toMillis(elapsedTimeNs) + " ms."
          + " Frames received: " + framesReceived + "."
          + " Dropped: " + framesDropped + "."
          + " Rendered: " + framesRendered + "."
          + " Render fps: " + fpsFormat.format(renderFps) + "."
          + " Average render time: " + averageTimeAsString(renderTimeNs, framesRendered) + "."
          + " Average swapBuffer time: "
          + averageTimeAsString(renderSwapBufferTimeNs, framesRendered) + ".");
      resetStatistics(currentTimeNs);
    }
  }

  private void logE(String string, Throwable e) {
    Logging.e(TAG, name + string, e);
  }

  private void logD(String string) {
    Logging.d(TAG, name + string);
  }

  private void logW(String string) {
    Logging.w(TAG, name + string);
  }
}
