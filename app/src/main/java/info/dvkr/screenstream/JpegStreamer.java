package info.dvkr.screenstream;

import android.util.Log;

import com.google.firebase.crash.FirebaseCrash;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class JpegStreamer {
    private static final String TAG = JpegStreamer.class.getSimpleName();
    private final Object lock = new Object();
    private final ExecutorService threadPool = Executors.newFixedThreadPool(2);

    private JpegStreamerThread jpegStreamerThread;
    private volatile boolean isThreadRunning;

    private class JpegStreamerThread extends Thread {
        private byte[] currentJPEG;
        private byte[] lastJPEG;
        private int sleepCount;
        private Future future;

        JpegStreamerThread() {
            super("JpegStreamerThread");
        }

        private void sendLastJPEGToClients() {
            sleepCount = 0;
            for (final Client currentClient : ApplicationContext.getClientQueue()) {
                currentClient.registerImage(lastJPEG);
                synchronized (lock) {
                    if (!isThreadRunning) return;
                    future = threadPool.submit(currentClient);
                }
                try {
                    future.get(ApplicationContext.getApplicationSettings().getClientTimeout(), TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    Log.d(TAG, "Remove client: " + currentClient.getClientAddress() + " " + e.toString());
                    FirebaseCrash.report(e);
                    currentClient.closeSocket();
                    ForegroundService.removeClient(currentClient);
                }
            }
        }

        public void run() {
            while (!isInterrupted()) {
                if (!isThreadRunning) break;
                currentJPEG = ApplicationContext.getJPEGQueue().poll();
                if (currentJPEG == null) {
                    try {
                        sleep(16);
                    } catch (InterruptedException e) {
                        continue;
                    }
                    sleepCount++;
                    if (sleepCount >= 60) sendLastJPEGToClients();
                } else {
                    lastJPEG = currentJPEG;
                    sendLastJPEGToClients();
                }

            }
        }

    } // JpegStreamerThread

    void addClient(final Socket clientSocket) {
        synchronized (lock) {
            if (!isThreadRunning) return;

            try {
                final Client newClient = new Client(clientSocket);
                newClient.sendHeader();
                ForegroundService.addClient(newClient);
//                Log.d(TAG, "Added one client: " + newClient.getClientAddress());
            } catch (IOException e) {
                //NOOP
            }
        }
    }

    void start() {
        synchronized (lock) {
            if (isThreadRunning) return;

            jpegStreamerThread = new JpegStreamerThread();
            jpegStreamerThread.start();

            isThreadRunning = true;
//            Log.d(TAG, "JPEG Streamer started");
        }
    }

    void stop() {
        synchronized (lock) {
            if (!isThreadRunning) return;
            isThreadRunning = false;

            jpegStreamerThread.interrupt();
            threadPool.shutdownNow();

            for (Client currentClient : ApplicationContext.getClientQueue())
                currentClient.closeSocket();

            ForegroundService.clearClients();
//            Log.d(TAG, "JPEG Streamer stopped");
        }
    }
}
