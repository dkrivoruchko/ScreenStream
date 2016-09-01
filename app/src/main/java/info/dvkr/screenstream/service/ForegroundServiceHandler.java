package info.dvkr.screenstream.service;

import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import com.google.firebase.crash.FirebaseCrash;


import info.dvkr.screenstream.data.ImageGenerator;

import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;
import static info.dvkr.screenstream.ScreenStreamApplication.getAppState;
import static info.dvkr.screenstream.ScreenStreamApplication.getWindowsManager;
import static info.dvkr.screenstream.ScreenStreamApplication.setIsStreamRunning;
import static info.dvkr.screenstream.service.ForegroundService.getImageGenerator;
import static info.dvkr.screenstream.service.ForegroundService.getMediaProjection;

final class ForegroundServiceHandler extends Handler {
    static final int HANDLER_START_STREAMING = 0;
    static final int HANDLER_STOP_STREAMING = 1;

    private static final int HANDLER_PAUSE_STREAMING = 10;
    private static final int HANDLER_RESUME_STREAMING = 11;
    private static final int HANDLER_DETECT_ROTATION = 20;

    private ImageGenerator mImageGenerator;
    private boolean mCurrentOrientation;

    ForegroundServiceHandler(final Looper looper) {
        super(looper);
    }

    @Override
    public void handleMessage(Message message) {
        switch (message.what) {
            case HANDLER_START_STREAMING:
                if (getAppState().isStreamRunning) break;
                removeMessages(HANDLER_DETECT_ROTATION);
                mCurrentOrientation = getOrientation();
                mImageGenerator = getImageGenerator();
                if (mImageGenerator != null) mImageGenerator.start();
                sendMessageDelayed(obtainMessage(HANDLER_DETECT_ROTATION), 250);
                setIsStreamRunning(true);
                break;
            case HANDLER_PAUSE_STREAMING:
                if (!getAppState().isStreamRunning) break;
                mImageGenerator = getImageGenerator();
                if (mImageGenerator != null) mImageGenerator.stop();
                sendMessageDelayed(obtainMessage(HANDLER_RESUME_STREAMING), 250);
                break;
            case HANDLER_RESUME_STREAMING:
                if (!getAppState().isStreamRunning) break;
                mImageGenerator = getImageGenerator();
                if (mImageGenerator != null) mImageGenerator.start();
                sendMessageDelayed(obtainMessage(HANDLER_DETECT_ROTATION), 250);
                break;
            case HANDLER_STOP_STREAMING:
                if (!getAppState().isStreamRunning) break;
                removeMessages(HANDLER_DETECT_ROTATION);
                removeMessages(HANDLER_STOP_STREAMING);
                mImageGenerator = getImageGenerator();
                if (mImageGenerator != null) mImageGenerator.stop();
                final MediaProjection mediaProjection = getMediaProjection();
                if (mediaProjection != null) mediaProjection.stop();
                setIsStreamRunning(false);
                break;
            case HANDLER_DETECT_ROTATION:
                if (!getAppState().isStreamRunning) break;
                boolean newOrientation = getOrientation();
                if (mCurrentOrientation == newOrientation) {
                    sendMessageDelayed(obtainMessage(HANDLER_DETECT_ROTATION), 250);
                    break;
                }
                mCurrentOrientation = newOrientation;
                obtainMessage(HANDLER_PAUSE_STREAMING).sendToTarget();
                break;
            default:
                FirebaseCrash.log("Cannot handle message");
        }
    }

    private boolean getOrientation() {
        final int rotation = getWindowsManager().getDefaultDisplay().getRotation();
        return rotation == ROTATION_0 || rotation == ROTATION_180;
    }
}