package info.dvkr.screenstream;

import com.google.firebase.crash.FirebaseCrash;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Random;

final class HttpServer {
    static final int SERVER_STATUS_UNKNOWN = -1;
    static final int SERVER_OK = 0;
    static final int SERVER_ERROR_ALREADY_RUNNING = 1;
    static final int SERVER_ERROR_PORT_IN_USE = 2;
    static final int SERVER_ERROR_UNKNOWN = 3;
    static final int SERVER_STOP = 10;
    static final int SERVER_SETTINGS_RESTART = 11;
    static final int SERVER_PIN_RESTART = 12;

    private static final int SEVER_SOCKET_TIMEOUT = 50;
    private static final String DEFAULT_ADDRESS = "/";
    private static final String DEFAULT_STREAM_ADDRESS = "/screen_stream.mjpeg";
    private static final String DEFAULT_ICO_ADDRESS = "/favicon.ico";
    private static final String DEFAULT_PIN_ADDRESS = "/?pin=";

    private final Object mLock = new Object();
    private volatile boolean isThreadRunning;

    private ServerSocket mServerSocket;
    private HttpServerThread mHttpServerThread;
    private JpegStreamer mJpegStreamer;

    private volatile boolean isPinEnabled;

    private String mCurrentPinUri = DEFAULT_PIN_ADDRESS;
    private String mCurrentStreamAddress = DEFAULT_STREAM_ADDRESS;

    private class HttpServerThread extends Thread {
        private Socket mClientSocket;
        private BufferedReader mBufferedReaderFromClient;
        private String mRequestUri;

        HttpServerThread() {
            super(HttpServerThread.class.getSimpleName());
        }

        public void run() {
            while (!isInterrupted()) {
                synchronized (mLock) {
                    if (!isThreadRunning) continue;
                    try {
                        mClientSocket = HttpServer.this.mServerSocket.accept();
                        mBufferedReaderFromClient = new BufferedReader(
                                new InputStreamReader(mClientSocket.getInputStream()));

                        final String requestLine = mBufferedReaderFromClient.readLine();
                        if (requestLine == null || !requestLine.startsWith("GET")) {
                            sendNotFound(mClientSocket);
                            continue;
                        }

                        final String[] requestUriArray = requestLine.split(" ");
                        if (requestUriArray.length >= 2) {
                            mRequestUri = requestUriArray[1];
                        } else {
                            sendNotFound(mClientSocket);
                            continue;
                        }

                        if (isPinEnabled) {
                            if (DEFAULT_ADDRESS.equals(mRequestUri)) {
                                sendPinRequestPage(mClientSocket, false);
                                continue;
                            }
                            if (mRequestUri.startsWith(DEFAULT_PIN_ADDRESS)) {
                                if (mRequestUri.equals(mCurrentPinUri))
                                    sendMainPage(mClientSocket, mCurrentStreamAddress);
                                else sendPinRequestPage(mClientSocket, true);
                                continue;
                            }
                        } else {
                            if (DEFAULT_ADDRESS.equals(mRequestUri)) {
                                sendMainPage(mClientSocket, mCurrentStreamAddress);
                                continue;
                            }
                        }

                        if (mCurrentStreamAddress.equals(mRequestUri)) {
                            HttpServer.this.mJpegStreamer.addClient(mClientSocket);
                            continue;
                        }

                        if (DEFAULT_ICO_ADDRESS.equals(mRequestUri)) {
                            sendFavicon(mClientSocket);
                            continue;
                        }

                        sendNotFound(mClientSocket);
                    } catch (SocketTimeoutException ignored) {
                    } catch (IOException e) {
                        FirebaseCrash.report(e);
                    }
                }
            }
        }

        private void sendPinRequestPage(final Socket socket, final boolean pinError) throws IOException {
            try (final OutputStreamWriter outputStreamWriter = new OutputStreamWriter(socket.getOutputStream())) {
                outputStreamWriter.write("HTTP/1.1 200 OK\r\n");
                outputStreamWriter.write("Content-Type: text/html\r\n");
                outputStreamWriter.write("Connection: close\r\n");
                outputStreamWriter.write("\r\n");
                outputStreamWriter.write(AppContext.getPinRequestHtmlPage(pinError));
                outputStreamWriter.write("\r\n");
                outputStreamWriter.flush();
            }
        }

        private void sendMainPage(final Socket socket, final String streamAddress) throws IOException {
            try (final OutputStreamWriter outputStreamWriter = new OutputStreamWriter(socket.getOutputStream())) {
                outputStreamWriter.write("HTTP/1.1 200 OK\r\n");
                outputStreamWriter.write("Content-Type: text/html\r\n");
                outputStreamWriter.write("Connection: close\r\n");
                outputStreamWriter.write("\r\n");
                outputStreamWriter.write(AppContext.getIndexHtmlPage(streamAddress));
                outputStreamWriter.write("\r\n");
                outputStreamWriter.flush();
            }
        }

        private void sendFavicon(Socket socket) throws IOException {
            try (final OutputStreamWriter outputStreamWriter = new OutputStreamWriter(socket.getOutputStream())) {
                outputStreamWriter.write("HTTP/1.1 200 OK\r\n");
                outputStreamWriter.write("Content-Type: image/png\r\n");
                outputStreamWriter.write("Connection: close\r\n");
                outputStreamWriter.write("\r\n");
                outputStreamWriter.flush();
                socket.getOutputStream().write(AppContext.getIconBytes());
                socket.getOutputStream().flush();
            }
        }

        private void sendNotFound(final Socket socket) throws IOException {
            try (final OutputStreamWriter outputStreamWriter = new OutputStreamWriter(socket.getOutputStream())) {
                outputStreamWriter.write("HTTP/1.1 301 Moved Permanently\r\n");
                outputStreamWriter.write("Location: " + AppContext.getServerAddress() + "\r\n");
                outputStreamWriter.write("Connection: close\r\n");
                outputStreamWriter.write("\r\n");
                outputStreamWriter.flush();
            }
        }

    }

    int start() {
        synchronized (mLock) {
            if (isThreadRunning) return SERVER_ERROR_ALREADY_RUNNING;

            mCurrentStreamAddress = DEFAULT_STREAM_ADDRESS;
            mCurrentPinUri = DEFAULT_PIN_ADDRESS;
            isPinEnabled = AppContext.getAppSettings().isEnablePin();
            if (isPinEnabled) {
                final String currentPin = AppContext.getAppSettings().getCurrentPin();
                mCurrentPinUri = DEFAULT_PIN_ADDRESS + currentPin;
                mCurrentStreamAddress = getRandomStreamAddress(currentPin);
            }

            try {
                mServerSocket = new ServerSocket(AppContext.getAppSettings().getSeverPort());
                mServerSocket.setSoTimeout(SEVER_SOCKET_TIMEOUT);

                mJpegStreamer = new JpegStreamer();
                mJpegStreamer.start();

                mHttpServerThread = new HttpServerThread();
                mHttpServerThread.start();

                isThreadRunning = true;
            } catch (BindException e) {
                return SERVER_ERROR_PORT_IN_USE;
            } catch (IOException e) {
                FirebaseCrash.report(e);
                return SERVER_ERROR_UNKNOWN;
            }
        }
        return SERVER_OK;
    }

    void stop(final int reason, final byte[] clientNotifyImage) {
        synchronized (mLock) {
            if (!isThreadRunning) return;
            isThreadRunning = false;

            mHttpServerThread.interrupt();
            mHttpServerThread = null;

            mJpegStreamer.stop(reason, clientNotifyImage);
            mJpegStreamer = null;

            try {
                mServerSocket.close();
            } catch (IOException e) {
                FirebaseCrash.report(e);
            }
            mServerSocket = null;
        }
    }

    private String getRandomStreamAddress(final String pin) {
        final String alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        final int randomLength = 10;
        final Random random = new Random(Long.parseLong(pin));
        final char[] randomPart = new char[randomLength];
        for (int i = 0; i < randomLength; i++) {
            randomPart[i] = alphabet.charAt(random.nextInt(alphabet.length()));
        }
        return "/screen_stream_" + String.valueOf(randomPart) + ".mjpeg";
    }
}