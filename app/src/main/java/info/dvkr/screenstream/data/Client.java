package info.dvkr.screenstream.data;

import com.google.firebase.crash.FirebaseCrash;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static info.dvkr.screenstream.ScreenStreamApplication.getAppData;
import static info.dvkr.screenstream.ScreenStreamApplication.getAppPreference;
import static info.dvkr.screenstream.ScreenStreamApplication.getMainActivityViewModel;

final class Client {
    final static int CLIENT_HEADER = 1;
    final static int CLIENT_IMAGE = 2;

    private volatile boolean isSending;
    private volatile boolean isClosing;
    private final Socket mClientSocket;
    private final OutputStreamWriter mOtputStreamWriter;
    private final ExecutorService mClientThreadPool = Executors.newFixedThreadPool(2);

    Client(final Socket socket) throws IOException {
        mClientSocket = socket;
        mOtputStreamWriter = new OutputStreamWriter(mClientSocket.getOutputStream(), "UTF8");

    }

    private void closeSocket() {
        isClosing = true;
        getAppData().getClientQueue().remove(Client.this);
        getMainActivityViewModel().setClients(getAppData().getClientQueue().size());
        try {
            mClientThreadPool.shutdownNow();
            mOtputStreamWriter.close();
            mClientSocket.close();
        } catch (IOException e) {
            FirebaseCrash.report(e);
        }

    }

    void sendClientData(final int dataType, final byte[] jpegImage, final boolean closeSocket) {
        if (isClosing) return;
        if (closeSocket && jpegImage == null) closeSocket();
        if (isSending) return;
        isSending = true;
        mClientThreadPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    mClientThreadPool.submit(new Callable<Object>() {
                        @Override
                        public Object call() throws Exception {
                            if (dataType == CLIENT_HEADER) sendHeader();
                            if (dataType == CLIENT_IMAGE) sendImage(jpegImage);
                            return null;
                        }
                    }).get(getAppPreference().getClientTimeout(), TimeUnit.MILLISECONDS);
                    if (closeSocket) closeSocket();
                    isSending = false;
                } catch (Exception e) {
                    closeSocket();
                }
            }
        });
    }

    private void sendHeader() throws IOException {
        mOtputStreamWriter.write("HTTP/1.1 200 OK\r\n");
        mOtputStreamWriter.write("Content-Type: multipart/x-mixed-replace; boundary=y5exa7CYPPqoASFONZJMz4Ky\r\n");
        mOtputStreamWriter.write("Cache-Control: no-store, no-cache, must-revalidate, pre-check=0, post-check=0, max-age=0\r\n");
        mOtputStreamWriter.write("Pragma: no-cache\r\n");
        mOtputStreamWriter.write("Connection: keep-alive\r\n");
        mOtputStreamWriter.write("\r\n");
        mOtputStreamWriter.flush();
    }

    private void sendImage(final byte[] jpegImage) throws IOException {
        mOtputStreamWriter.write("--y5exa7CYPPqoASFONZJMz4Ky\r\n");
        mOtputStreamWriter.write("Content-Type: image/jpeg\r\n");
        mOtputStreamWriter.write("Content-Length: " + jpegImage.length + "\r\n");
        mOtputStreamWriter.write("\r\n");
        mOtputStreamWriter.flush();
        mClientSocket.getOutputStream().write(jpegImage);
        mClientSocket.getOutputStream().flush();
        mOtputStreamWriter.write("\r\n");
        mOtputStreamWriter.flush();
    }
}