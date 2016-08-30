package info.dvkr.screenstream;

import com.google.firebase.crash.FirebaseCrash;

import java.io.IOException;
import java.net.Socket;

import static info.dvkr.screenstream.AppContext.getAppState;
import static info.dvkr.screenstream.AppContext.getAppViewState;

final class JpegStreamer {
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
                for (final Client currentClient : getAppState().mClientQueue) {
                    currentClient.sendClientData(HttpServer.SERVER_OK, Client.CLIENT_IMAGE, mLastJpeg);
                }
            }
        }
    }

    void addClient(final Socket clientSocket) {
        synchronized (mLock) {
            if (!isThreadRunning) return;
            try {
                final Client newClient = new Client(clientSocket);
                newClient.sendClientData(HttpServer.SERVER_OK, Client.CLIENT_HEADER, null);
                getAppState().mClientQueue.add(newClient);
                getAppViewState().clients.set(getAppState().mClientQueue.size());
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

            for (Client currentClient : getAppState().mClientQueue) {
                currentClient.sendClientData(reason, Client.CLIENT_IMAGE, clientNotifyImage);
            }

            getAppState().mClientQueue.clear();
            getAppViewState().clients.set(0);
        }
    }
}
