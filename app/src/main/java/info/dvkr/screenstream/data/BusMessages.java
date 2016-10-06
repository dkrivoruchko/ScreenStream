package info.dvkr.screenstream.data;

public final class BusMessages {
    public static final String MESSAGE_ACTION_STREAMING_TRY_START = "MESSAGE_ACTION_STREAMING_TRY_START";
    public static final String MESSAGE_ACTION_STREAMING_START = "MESSAGE_ACTION_STREAMING_START";
    public static final String MESSAGE_ACTION_STREAMING_STOP = "MESSAGE_ACTION_STREAMING_STOP";
    public static final String MESSAGE_ACTION_HTTP_RESTART = "MESSAGE_ACTION_HTTP_RESTART";
    public static final String MESSAGE_ACTION_PIN_UPDATE = "MESSAGE_ACTION_PIN_UPDATE";

    public static final String MESSAGE_STATUS_HTTP_OK = "MESSAGE_STATUS_HTTP_OK";
    public static final String MESSAGE_STATUS_HTTP_ERROR_NO_IP = "MESSAGE_STATUS_HTTP_ERROR_NO_IP";
    public static final String MESSAGE_STATUS_HTTP_ERROR_PORT_IN_USE = "MESSAGE_STATUS_HTTP_ERROR_PORT_IN_USE";
    public static final String MESSAGE_STATUS_HTTP_ERROR_UNKNOWN = "MESSAGE_STATUS_HTTP_ERROR_UNKNOWN";

    public static final String MESSAGE_STATUS_IMAGE_GENERATOR_ERROR = "MESSAGE_STATUS_IMAGE_GENERATOR_ERROR";

    private final String message;

    public BusMessages(final String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}