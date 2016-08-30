package info.dvkr.screenstream;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;

class AppState {
    final ConcurrentLinkedDeque<byte[]> mJPEGQueue = new ConcurrentLinkedDeque<>();
    final ConcurrentLinkedQueue<Client> mClientQueue = new ConcurrentLinkedQueue<>();
    volatile boolean isStreamRunning;
    volatile int mHttpServerStatus = HttpServer.SERVER_STATUS_UNKNOWN;
}
