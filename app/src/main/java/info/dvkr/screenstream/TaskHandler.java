package info.dvkr.screenstream;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import com.google.firebase.crash.FirebaseCrash;

import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;

final class TaskHandler extends Handler {
    static final int HANDLER_START_STREAMING = 0;
    static final int HANDLER_STOP_STREAMING = 1;

    private static final int HANDLER_PAUSE_STREAMING = 4;
    private static final int HANDLER_RESUME_STREAMING = 5;
    private static final int HANDLER_DETECT_ROTATION = 10;

    private int currentOrientation;

    TaskHandler(final Looper looper) {
        super(looper);
    }

    @Override
    public void handleMessage(Message message) {
        switch (message.what) {
            case HANDLER_START_STREAMING:
                if (ApplicationContext.isStreamRunning()) break;
                removeMessages(HANDLER_DETECT_ROTATION);
                currentOrientation = getOrientation();
                ForegroundService.getImageGenerator().start();
                sendMessageDelayed(obtainMessage(HANDLER_DETECT_ROTATION), 250);
                ApplicationContext.setIsStreamRunning(true);
                break;
            case HANDLER_PAUSE_STREAMING:
                if (!ApplicationContext.isStreamRunning()) break;
                ForegroundService.getImageGenerator().stop();
                sendMessageDelayed(obtainMessage(HANDLER_RESUME_STREAMING), 250);
                break;
            case HANDLER_RESUME_STREAMING:
                if (!ApplicationContext.isStreamRunning()) break;
                ForegroundService.getImageGenerator().start();
                sendMessageDelayed(obtainMessage(HANDLER_DETECT_ROTATION), 250);
                break;
            case HANDLER_STOP_STREAMING:
                if (!ApplicationContext.isStreamRunning()) break;
                removeMessages(HANDLER_DETECT_ROTATION);
                removeMessages(HANDLER_STOP_STREAMING);
                ForegroundService.getImageGenerator().stop();
                ApplicationContext.getMediaProjection().stop();
                ApplicationContext.setIsStreamRunning(false);
                break;
            case HANDLER_DETECT_ROTATION:
                if (!ApplicationContext.isStreamRunning()) break;
                final int newOrientation = getOrientation();
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

    private int getOrientation() {
        final int rotation = ApplicationContext.getWindowsManager().getDefaultDisplay().getRotation();
        if (rotation == ROTATION_0 || rotation == ROTATION_180) return 0;
        return 1;
    }
}