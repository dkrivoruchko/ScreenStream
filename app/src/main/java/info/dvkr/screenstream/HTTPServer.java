package info.dvkr.screenstream;

import android.util.Log;

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

final class HTTPServer {
    static final int SERVER_STATUS_UNKNOWN = -1;
    static final int SERVER_OK = 0;
    static final int SERVER_ERROR_ALREADY_RUNNING = 1;
    static final int SERVER_ERROR_PORT_IN_USE = 2;
    static final int SERVER_ERROR_UNKNOWN = 3;
    static final int SERVER_STOP = 10;
    static final int SERVER_SETTINGS_RESTART = 11;
    static final int SERVER_PIN_RESTART = 12;

    private static final int SEVER_SOCKET_TIMEOUT = 50;
    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int ALPHABET_COUNT = ALPHABET.length();
    private static final String DEFAULT_ADDRESS = "/";
    private static final String DEFAULT_PIN_ADDRESS = "/?pin=";
    private static final String DEFAULT_STREAM_ADDRESS = "/screen_stream.mjpeg";
    private static final String DEFAULT_ICO_ADDRESS = "/favicon.ico";

    private final Object lock = new Object();

    private volatile boolean isThreadRunning;

    private ServerSocket serverSocket;
    private HTTPServerThread httpServerThread;
    private JpegStreamer jpegStreamer;

    private boolean isPinEnabled;
    private String currentPinURI = "";
    private String currentStreamAddress = DEFAULT_STREAM_ADDRESS;

    private class HTTPServerThread extends Thread {
        private Socket clientSocket;
        private BufferedReader bufferedReaderFromClient;
        private String requestLine;
        private String[] requestUriArray;
        private String requestUri;

        HTTPServerThread() {
            super("HTTPServerThread");
        }

        public void run() {
            while (!isInterrupted()) {
                synchronized (lock) {
                    if (!isThreadRunning) continue;
                    try {
                        clientSocket = HTTPServer.this.serverSocket.accept();
                        bufferedReaderFromClient = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                        requestLine = bufferedReaderFromClient.readLine();
                        if (requestLine == null) continue;

                        requestUriArray = requestLine.split(" ");
                        if (requestUriArray.length >= 2) requestUri = requestUriArray[1];
                        else requestUri = "URI_NOT_SET";

                        Log.wtf(">>>>>>>>>> requestLine", requestLine);

                        if (DEFAULT_ADDRESS.equals(requestUri)) {
                            if (isPinEnabled) {
                                sendPinRequestPage(clientSocket, false);
                            } else {
                                sendMainPage(clientSocket, currentStreamAddress);
                            }
                            continue;
                        }

                        if (isPinEnabled) {
                            if (currentPinURI.equals(requestUri)) {
                                sendMainPage(clientSocket, currentStreamAddress);
                                continue;
                            } else if (requestUri.contains(DEFAULT_PIN_ADDRESS)) {
                                sendPinRequestPage(clientSocket, true);
                                continue;
                            }
                        }

                        if (currentStreamAddress.equals(requestUri)) {
                            HTTPServer.this.jpegStreamer.addClient(clientSocket);
                            continue;
                        }

                        if (DEFAULT_ICO_ADDRESS.equals(requestUri)) {
                            sendFavicon(clientSocket);
                            continue;
                        }

                        sendNotFound(clientSocket);
                    } catch (SocketTimeoutException ignored) {
                    } catch (IOException e) {
                        FirebaseCrash.report(e);
                    }
                }
            } // while
        } // run()

        private void sendPinRequestPage(final Socket socket, final boolean pinError) throws IOException {
            try (final OutputStreamWriter outputStreamWriter = new OutputStreamWriter(socket.getOutputStream())) {
                outputStreamWriter.write("HTTP/1.1 200 OK\r\n");
                outputStreamWriter.write("Content-Type: text/html\r\n");
                outputStreamWriter.write("Connection: close\r\n");
                outputStreamWriter.write("\r\n");
                outputStreamWriter.write(ApplicationContext.getPinRequestHTMLPage(pinError));
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
                outputStreamWriter.write(ApplicationContext.getIndexHTMLPage(streamAddress));
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
                socket.getOutputStream().write(ApplicationContext.getIconBytes());
                socket.getOutputStream().flush();
            }
        }

        private void sendNotFound(final Socket socket) throws IOException {
            try (final OutputStreamWriter outputStreamWriter = new OutputStreamWriter(socket.getOutputStream())) {
                outputStreamWriter.write("HTTP/1.1 301 Moved Permanently\r\n");
                outputStreamWriter.write("Location: " + ApplicationContext.getServerAddress() + "\r\n");
                outputStreamWriter.write("Connection: close\r\n");
                outputStreamWriter.write("\r\n");
                outputStreamWriter.flush();
            }
        }

    }

    int start() {
        synchronized (lock) {
            if (isThreadRunning) return SERVER_ERROR_ALREADY_RUNNING;
            try {
                isPinEnabled = ApplicationContext.getApplicationSettings().isEnablePin();
                if (isPinEnabled) {
                    final String currentPin = ApplicationContext.getApplicationSettings().getUserPin();
                    currentStreamAddress = getStreamAddress(currentPin);
                    currentPinURI = DEFAULT_PIN_ADDRESS + currentPin;
                } else {
                    currentStreamAddress = DEFAULT_STREAM_ADDRESS;
                    currentPinURI = "";
                }

                serverSocket = new ServerSocket(ApplicationContext.getApplicationSettings().getSeverPort());
                serverSocket.setSoTimeout(SEVER_SOCKET_TIMEOUT);

                jpegStreamer = new JpegStreamer();
                jpegStreamer.start();

                httpServerThread = new HTTPServerThread();
                httpServerThread.start();

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
        synchronized (lock) {
            if (!isThreadRunning) return;
            isThreadRunning = false;

            httpServerThread.interrupt();
            httpServerThread = null;

            jpegStreamer.stop(reason, clientNotifyImage);
            jpegStreamer = null;

            try {
                serverSocket.close();
            } catch (IOException e) {
                FirebaseCrash.report(e);
            }
            serverSocket = null;
        }
    }

    private String getStreamAddress(final String pin) {
        final int randomLength = 10;
        final Random random = new Random(Long.parseLong(pin));
        final char[] randomPart = new char[randomLength];
        for (int i = 0; i < randomLength; i++)
            randomPart[i] = ALPHABET.charAt(random.nextInt(ALPHABET_COUNT));
        return "/screen_stream_" + String.valueOf(randomPart) + ".mjpeg";
    }
}