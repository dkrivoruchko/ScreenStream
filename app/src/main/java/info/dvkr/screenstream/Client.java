package info.dvkr.screenstream;

import android.util.Log;

import com.google.firebase.crash.FirebaseCrash;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class Client {
    final static int CLIENT_HEADER = 1;
    final static int CLIENT_IMAGE = 2;

    private final Socket clientSocket;
    private final OutputStreamWriter outputStreamWriter;
    private final ExecutorService clientThread = Executors.newFixedThreadPool(2);

    Client(final Socket socket) throws IOException {
        this.clientSocket = socket;
        outputStreamWriter = new OutputStreamWriter(clientSocket.getOutputStream());
    }

    private void closeSocket() {
        try {
            clientThread.shutdownNow();
            outputStreamWriter.close();
            clientSocket.close();
        } catch (IOException e) {
            FirebaseCrash.report(e);
        }
        ForegroundService.removeClient(Client.this);
    }

    void sendClientData(final int httpServerStatus, final int dataType, final byte[] jpegImage) {
        if (dataType == CLIENT_IMAGE && jpegImage == null)
            if (httpServerStatus == HTTPServer.SERVER_OK) {
                return;
            } else {
                closeSocket();
                return;
            }

        clientThread.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    clientThread.submit(new Callable<Object>() {
                        @Override
                        public Object call() throws Exception {
                            if (dataType == CLIENT_HEADER) sendHeader();
                            if (dataType == CLIENT_IMAGE) sendImage(jpegImage);
                            return null;
                        }
                    }).get(ApplicationContext.getApplicationSettings().getClientTimeout(), TimeUnit.MILLISECONDS);

                    if (httpServerStatus != HTTPServer.SERVER_OK) closeSocket();
                } catch (Exception e) {
                    Log.d("JpegStreamer", "Remove client: " + clientSocket.getInetAddress() + ":" + clientSocket.getPort() + " " + e.toString());
                    closeSocket();
                    FirebaseCrash.report(e);
                }
            }
        });
    }

    private void sendHeader() throws IOException {
        outputStreamWriter.write("HTTP/1.1 200 OK\r\n");
        outputStreamWriter.write("Content-Type: multipart/x-mixed-replace; boundary=y5exa7CYPPqoASFONZJMz4Ky\r\n");
        outputStreamWriter.write("Cache-Control: no-store, no-cache, must-revalidate, pre-check=0, post-check=0, max-age=0\r\n");
        outputStreamWriter.write("Pragma: no-cache\r\n");
        outputStreamWriter.write("Connection: keep-alive\r\n");
        outputStreamWriter.write("\r\n");
        outputStreamWriter.flush();
    }

    private void sendImage(final byte[] jpegImage) throws IOException {
        outputStreamWriter.write("--y5exa7CYPPqoASFONZJMz4Ky\r\n");
        outputStreamWriter.write("Content-Type: image/jpeg\r\n");
        outputStreamWriter.write("Content-Length: " + jpegImage.length + "\r\n");
        outputStreamWriter.write("\r\n");
        outputStreamWriter.flush();
        clientSocket.getOutputStream().write(jpegImage);
        clientSocket.getOutputStream().flush();
        outputStreamWriter.write("\r\n");
        outputStreamWriter.flush();
    }
}
