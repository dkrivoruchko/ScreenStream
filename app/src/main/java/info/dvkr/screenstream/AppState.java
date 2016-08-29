package info.dvkr.screenstream;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;

class AppState {
    final ConcurrentLinkedDeque<byte[]> JPEGQueue = new ConcurrentLinkedDeque<>();
    final ConcurrentLinkedQueue<Client> clientQueue = new ConcurrentLinkedQueue<>();
    volatile boolean isStreamRunning;
    volatile int httpServerStatus = HTTPServer.SERVER_STATUS_UNKNOWN;
}
