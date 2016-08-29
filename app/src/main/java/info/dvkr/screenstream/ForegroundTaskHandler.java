package info.dvkr.screenstream;

import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import com.google.firebase.crash.FirebaseCrash;

import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;
import static info.dvkr.screenstream.AppContext.getAppState;
import static info.dvkr.screenstream.AppContext.getWindowsManager;
import static info.dvkr.screenstream.AppContext.setIsStreamRunning;

final class ForegroundTaskHandler extends Handler {
    static final int HANDLER_START_STREAMING = 0;
    static final int HANDLER_STOP_STREAMING = 1;

    private static final int HANDLER_PAUSE_STREAMING = 4;
    private static final int HANDLER_RESUME_STREAMING = 5;
    private static final int HANDLER_DETECT_ROTATION = 10;

    private ImageGenerator imageGenerator;
    private boolean currentOrientation;
    private boolean newOrientation;
    private int rotation;

    ForegroundTaskHandler(final Looper looper) {
        super(looper);
    }

    @Override
    public void handleMessage(Message message) {
        switch (message.what) {
            case HANDLER_START_STREAMING:
                if (getAppState().isStreamRunning) break;
                removeMessages(HANDLER_DETECT_ROTATION);
                currentOrientation = getOrientation();
                imageGenerator = ForegroundService.getImageGenerator();
                if (imageGenerator != null) imageGenerator.start();
                sendMessageDelayed(obtainMessage(HANDLER_DETECT_ROTATION), 250);
                setIsStreamRunning(true);
                break;
            case HANDLER_PAUSE_STREAMING:
                if (!getAppState().isStreamRunning) break;
                imageGenerator = ForegroundService.getImageGenerator();
                if (imageGenerator != null) imageGenerator.stop();
                sendMessageDelayed(obtainMessage(HANDLER_RESUME_STREAMING), 250);
                break;
            case HANDLER_RESUME_STREAMING:
                if (!getAppState().isStreamRunning) break;
                imageGenerator = ForegroundService.getImageGenerator();
                if (imageGenerator != null) imageGenerator.start();
                sendMessageDelayed(obtainMessage(HANDLER_DETECT_ROTATION), 250);
                break;
            case HANDLER_STOP_STREAMING:
                if (!getAppState().isStreamRunning) break;
                removeMessages(HANDLER_DETECT_ROTATION);
                removeMessages(HANDLER_STOP_STREAMING);
                imageGenerator = ForegroundService.getImageGenerator();
                if (imageGenerator != null) imageGenerator.stop();
                final MediaProjection mediaProjection = ForegroundService.getMediaProjection();
                if (mediaProjection != null) mediaProjection.stop();
                setIsStreamRunning(false);
                break;
            case HANDLER_DETECT_ROTATION:
                if (!getAppState().isStreamRunning) break;
                newOrientation = getOrientation();
                if (currentOrientation == newOrientation) {
                    sendMessageDelayed(obtainMessage(HANDLER_DETECT_ROTATION), 250);
                    break;
                }
                currentOrientation = newOrientation;
                obtainMessage(HANDLER_PAUSE_STREAMING).sendToTarget();
                break;
            default:
                FirebaseCrash.log("Cannot handle message");
        }
    }

    private boolean getOrientation() {
        rotation = getWindowsManager().getDefaultDisplay().getRotation();
        return rotation == ROTATION_0 || rotation == ROTATION_180;
    }
}