package dev.swirlit.demo.common.web;

import java.io.IOException;

/** Thrown while reading a request body that grows past the configured limit without declaring its length. */
public class PayloadTooLargeException extends IOException {

    public PayloadTooLargeException(long limit) {
        super("Request body exceeds " + limit + " bytes");
    }
}
