package info.dvkr.screenstream.data;

import com.google.firebase.crash.FirebaseCrash;

import java.io.IOException;
import java.net.Socket;

import static info.dvkr.screenstream.ScreenStreamApplication.getAppState;
import static info.dvkr.screenstream.ScreenStreamApplication.getMainActivityViewModel;

final class ImageToClientDispatcher {
    private final Object mLock = new Object();
    private JpegStreamerThread mJpegStreamerThread;
    private volatile boolean isThreadRunning;

    private class JpegStreamerThread extends Thread {
        private byte[] mCurrentJpeg;
        private byte[] mLastJpeg;
        private int mSleepCount;

        JpegStreamerThread() {
            super(JpegStreamerThread.class.getSimpleName());
        }

        public void run() {
            while (!isInterrupted()) {
                if (!isThreadRunning) break;
                mCurrentJpeg = getAppState().mJPEGQueue.poll();

                if (mCurrentJpeg == null) {
                    try {
                        sleep(10);
                    } catch (InterruptedException e) {
                        continue;
                    }
                    mSleepCount++;
                    if (mSleepCount >= 50) sendLastJPEGToClients();
                } else {
                    mLastJpeg = mCurrentJpeg;
                    sendLastJPEGToClients();
                }
            }
        }

        private void sendLastJPEGToClients() {
            mSleepCount = 0;
            synchronized (mLock) {
                if (!isThreadRunning) return;
                for (final ImageToClientStreamer currentImageToClientStreamer : getAppState().mImageToClientStreamerQueue) {
                    currentImageToClientStreamer.sendClientData(HttpServer.SERVER_OK, ImageToClientStreamer.CLIENT_IMAGE, mLastJpeg);
                }
            }
        }
    }

    void addClient(final Socket clientSocket) {
        synchronized (mLock) {
            if (!isThreadRunning) return;
            try {
                final ImageToClientStreamer newImageToClientStreamer = new ImageToClientStreamer(clientSocket);
                newImageToClientStreamer.sendClientData(HttpServer.SERVER_OK, ImageToClientStreamer.CLIENT_HEADER, null);
                getAppState().mImageToClientStreamerQueue.add(newImageToClientStreamer);
                getMainActivityViewModel().setClients(getAppState().mImageToClientStreamerQueue.size());
            } catch (IOException e) {
                FirebaseCrash.report(e);
            }
        }
    }

    void start() {
        synchronized (mLock) {
            if (isThreadRunning) return;
            mJpegStreamerThread = new JpegStreamerThread();
            mJpegStreamerThread.start();
            isThreadRunning = true;
        }
    }

    void stop(final int reason, final byte[] clientNotifyImage) {
        synchronized (mLock) {
            if (!isThreadRunning) return;
            isThreadRunning = false;
            mJpegStreamerThread.interrupt();

            for (ImageToClientStreamer currentImageToClientStreamer : getAppState().mImageToClientStreamerQueue) {
                currentImageToClientStreamer.sendClientData(reason, ImageToClientStreamer.CLIENT_IMAGE, clientNotifyImage);
            }

            getAppState().mImageToClientStreamerQueue.clear();
            getMainActivityViewModel().setClients(0);
        }
    }
}
