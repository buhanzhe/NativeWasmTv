package xiao.bu.tv;

import java.io.IOException;

/** Explicit client errors whose messages are safe to return over HTTP. */
final class ControlRequestException extends IOException {
    final int status;
    ControlRequestException(int status, String message) {
        super(message);
        this.status = status;
    }
}
