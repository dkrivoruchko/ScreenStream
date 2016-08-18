package info.dvkr.screenstream;

import com.google.firebase.crash.FirebaseCrash;

import java.io.IOException;
import java.net.Socket;

final class JpegStreamer {
    private final Object lock = new Object();
    private JpegStreamerThread jpegStreamerThread;
    private volatile boolean isThreadRunning;

    private class JpegStreamerThread extends Thread {
        private byte[] currentJPEG;
        private byte[] lastJPEG;
        private int sleepCount;

        JpegStreamerThread() {
            super("JpegStreamerThread");
        }

        public void run() {
            while (!isInterrupted()) {
                if (!isThreadRunning) break;
                currentJPEG = ApplicationContext.getJPEGQueue().poll();

                if (currentJPEG == null) {
                    try {
                        sleep(10);
                    } catch (InterruptedException e) {
                        continue;
                    }
                    sleepCount++;
                    if (sleepCount >= 50) sendLastJPEGToClients();
                } else {
                    lastJPEG = currentJPEG;
                    sendLastJPEGToClients();
                }
            }
        }

        private void sendLastJPEGToClients() {
            sleepCount = 0;
            synchronized (lock) {
                if (!isThreadRunning) return;
                for (final Client currentClient : ApplicationContext.getClientQueue())
                    currentClient.sendClientData(HTTPServer.SERVER_OK, Client.CLIENT_IMAGE, lastJPEG);
            }
        }
    }

    void addClient(final Socket clientSocket) {
        synchronized (lock) {
            if (!isThreadRunning) return;
            try {
                final Client newClient = new Client(clientSocket);
                newClient.sendClientData(HTTPServer.SERVER_OK, Client.CLIENT_HEADER, null);
                ForegroundService.addClient(newClient);
            } catch (IOException e) {
                FirebaseCrash.report(e);
            }
        }
    }

    void start() {
        synchronized (lock) {
            if (isThreadRunning) return;
            jpegStreamerThread = new JpegStreamerThread();
            jpegStreamerThread.start();
            isThreadRunning = true;
        }
    }

    void stop(final int reason, final byte[] clientNotifyImage) {
        synchronized (lock) {
            if (!isThreadRunning) return;
            isThreadRunning = false;
            jpegStreamerThread.interrupt();
            for (Client currentClient : ApplicationContext.getClientQueue())
                currentClient.sendClientData(reason, Client.CLIENT_IMAGE, clientNotifyImage);

            ForegroundService.clearClients();
        }
    }
}
