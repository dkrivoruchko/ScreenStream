package info.dvkr.screenstream.service;

import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import com.google.firebase.crash.FirebaseCrash;

import info.dvkr.screenstream.data.ImageGenerator;

import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;
import static info.dvkr.screenstream.ScreenStreamApplication.getAppData;
import static info.dvkr.screenstream.service.ForegroundService.getMediaProjection;

final class ForegroundServiceHandler extends Handler {
    static final int HANDLER_START_STREAMING = 0;
    static final int HANDLER_STOP_STREAMING = 1;

    private static final int HANDLER_PAUSE_STREAMING = 10;
    private static final int HANDLER_RESUME_STREAMING = 11;
    private static final int HANDLER_DETECT_ROTATION = 20;

    private boolean mCurrentOrientation;
    private final ImageGenerator mImageGenerator;

    ForegroundServiceHandler(final Looper looper) {
        super(looper);
        mImageGenerator = new ImageGenerator();
    }

    @Override
    public void handleMessage(Message message) {
        switch (message.what) {
            case HANDLER_START_STREAMING:
                if (getAppData().isStreamRunning()) break;
                removeMessages(HANDLER_DETECT_ROTATION);
                mCurrentOrientation = getOrientation();
                mImageGenerator.start();
                sendMessageDelayed(obtainMessage(HANDLER_DETECT_ROTATION), 250);
                getAppData().setStreamRunning(true);
                break;
            case HANDLER_PAUSE_STREAMING:
                if (!getAppData().isStreamRunning()) break;
                mImageGenerator.stop();
                sendMessageDelayed(obtainMessage(HANDLER_RESUME_STREAMING), 250);
                break;
            case HANDLER_RESUME_STREAMING:
                if (!getAppData().isStreamRunning()) break;
                mImageGenerator.start();
                sendMessageDelayed(obtainMessage(HANDLER_DETECT_ROTATION), 250);
                break;
            case HANDLER_STOP_STREAMING:
                if (!getAppData().isStreamRunning()) break;
                removeMessages(HANDLER_DETECT_ROTATION);
                removeMessages(HANDLER_STOP_STREAMING);
                mImageGenerator.stop();
                final MediaProjection mediaProjection = getMediaProjection();
                if (mediaProjection != null) mediaProjection.stop();
                getAppData().setStreamRunning(false);
                break;
            case HANDLER_DETECT_ROTATION:
                if (!getAppData().isStreamRunning()) break;
                final boolean newOrientation = getOrientation();
                if (mCurrentOrientation != newOrientation) {
                    mCurrentOrientation = newOrientation;
                    obtainMessage(HANDLER_PAUSE_STREAMING).sendToTarget();
                } else {
                    sendMessageDelayed(obtainMessage(HANDLER_DETECT_ROTATION), 250);
                }
                break;
            default:
                FirebaseCrash.log("Cannot handle message");
        }
    }

    private boolean getOrientation() {
        final int rotation = getAppData().getWindowsManager().getDefaultDisplay().getRotation();
        return rotation == ROTATION_0 || rotation == ROTATION_180;
    }
}