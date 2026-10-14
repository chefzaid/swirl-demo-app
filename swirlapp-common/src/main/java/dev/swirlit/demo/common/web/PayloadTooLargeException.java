package dev.swirlit.demo.common.web;

import java.io.IOException;

/** Thrown while reading a request body that grows past the configured limit without declaring its length. */
public class PayloadTooLargeException extends IOException {

    public static final String TITLE = "Payload too large";
    public static final String DETAIL = "The request body exceeds the size limit";

    public PayloadTooLargeException(long limit) {
        super("Request body exceeds " + limit + " bytes");
    }

    /** Whether reading an oversized body caused {@code exception}, however deeply a framework wrapped it. */
    public static boolean causes(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof PayloadTooLargeException) {
                return true;
            }
        }
        return false;
    }
}
