package dev.swirlit.demo.common.event;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.common.header.Headers;

/**
 * Event identity carried in the {@value #HEADER} record header. An ID stays the same when a record is published
 * again (an outbox retry, a redelivered request), so a consumer can recognise a duplicate it already processed.
 */
public final class EventIds {

    public static final String HEADER = "swirl-event-id";

    private EventIds() {
    }

    /** The ID of an order validation request published from the order outbox row {@code outboxId}. */
    public static String ofOutboxRow(long outboxId) {
        return "order-outbox-" + outboxId;
    }

    /** The ID of the result answering request {@code requestId}: every answer to one request shares it. */
    public static String resultOf(String requestId) {
        return requestId + ":result";
    }

    public static void write(Headers headers, String eventId) {
        if (eventId != null) {
            headers.add(HEADER, eventId.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static String read(byte[] header) {
        return header == null ? null : new String(header, StandardCharsets.UTF_8);
    }
}
