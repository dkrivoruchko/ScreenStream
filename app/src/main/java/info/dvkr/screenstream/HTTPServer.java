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

final class HTTPServer {
    static final int SERVER_STATUS_UNKNOWN = -1;
    static final int SERVER_OK = 0;
    static final int SERVER_ERROR_ALREADY_RUNNING = 1;
    static final int SERVER_ERROR_PORT_IN_USE = 2;
    static final int SERVER_ERROR_UNKNOWN = 3;

    private final Object lock = new Object();
    private static final int SEVER_SOCKET_TIMEOUT = 50;

    private volatile boolean isThreadRunning;

    private ServerSocket serverSocket;
    private HTTPServerThread httpServerThread;
    private JpegStreamer jpegStreamer;

    private class HTTPServerThread extends Thread {


        HTTPServerThread() {
            super("HTTPServerThread");
        }

        public void run() {
            while (!isInterrupted()) {
                synchronized (lock) {
                    if (!isThreadRunning) continue;
                    try {
                        final Socket clientSocket = HTTPServer.this.serverSocket.accept();
                        final BufferedReader bufferedReaderFromClient = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                        final String requestLine = bufferedReaderFromClient.readLine();

                        if (requestLine == null) continue;

                        final String requestUri = requestLine.split(" ")[1];
                        switch (requestUri) {
                            case "/":
                                sendMainPage(clientSocket);
                                break;
                            case "/screen_stream.mjpeg":
                                HTTPServer.this.jpegStreamer.addClient(clientSocket);
                                break;
                            case "/favicon.ico":
                                sendFavicon(clientSocket);
                                break;
                            default:
                                sendNotFound(clientSocket);
                        }
                    } catch (SocketTimeoutException ex) {
                        // NOOP
                    } catch (IOException e) {
                        FirebaseCrash.report(e);
                    }
                }
            } // while
        } // run()

        private void sendMainPage(final Socket socket) throws IOException {
            try (final OutputStreamWriter outputStreamWriter = new OutputStreamWriter(socket.getOutputStream())) {
                outputStreamWriter.write("HTTP/1.1 200 OK\r\n");
                outputStreamWriter.write("Content-Type: text/html\r\n");
                outputStreamWriter.write("Connection: close\r\n");
                outputStreamWriter.write("\r\n");
                outputStreamWriter.write(ApplicationContext.getIndexHtmlPage());
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
                serverSocket = new ServerSocket(ApplicationContext.getApplicationSettings().getSeverPort());
                serverSocket.setSoTimeout(SEVER_SOCKET_TIMEOUT);

                jpegStreamer = new JpegStreamer();
                jpegStreamer.start();

                httpServerThread = new HTTPServerThread();
                httpServerThread.start();

                isThreadRunning = true;
//            Log.d(TAG, "HTTP server started on port: " + ApplicationContext.getApplicationSettings().getSeverPort());
            } catch (BindException e) {
                return SERVER_ERROR_PORT_IN_USE;
            } catch (IOException e) {
                FirebaseCrash.report(e);
                return SERVER_ERROR_UNKNOWN;
            }
        }
        return SERVER_OK;
    }

    void stop() {
        synchronized (lock) {
            if (!isThreadRunning) return;
            isThreadRunning = false;

            httpServerThread.interrupt();
            httpServerThread = null;

            jpegStreamer.stop();
            jpegStreamer = null;

            try {
                serverSocket.close();
            } catch (IOException e) {
                FirebaseCrash.report(e);
            }
            serverSocket = null;
//            Log.d(TAG, "HTTP server stopped");
        }
    }
}