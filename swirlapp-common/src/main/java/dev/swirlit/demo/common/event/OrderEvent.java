package dev.swirlit.demo.common.event;

import java.time.Instant;

import dev.swirlit.demo.common.domain.OrderStatus;

/**
 * The order request and result event. {@code schemaVersion} is the contract's major version: additive changes
 * (a new optional field) keep it, and only a breaking change raises it. Readers ignore fields they do not know
 * and refuse a major version newer than {@link #SCHEMA_VERSION}, so that record is dead-lettered instead of
 * misread. See docs/adr/0012-event-schema-compatibility.md.
 */
public record OrderEvent(
        Integer schemaVersion,
        Long orderId,
        Long userId,
        Long productId,
        String userName,
        OrderStatus status,
        Instant occurredAt) {

    public static final int SCHEMA_VERSION = 1;

    public OrderEvent {
        // Records written before the field existed are version 1.
        if (schemaVersion == null) {
            schemaVersion = 1;
        }
    }

    /** An event in the current schema version. */
    public OrderEvent(Long orderId, Long userId, Long productId, String userName, OrderStatus status,
            Instant occurredAt) {
        this(SCHEMA_VERSION, orderId, userId, productId, userName, status, occurredAt);
    }

    public OrderEvent withResult(String resolvedUserName, OrderStatus resolvedStatus) {
        return new OrderEvent(orderId, userId, productId, resolvedUserName, resolvedStatus, Instant.now());
    }

    /**
     * Rejects an event from a newer, incompatible producer. The exception is not retried, so the record goes
     * to the dead-letter topic and waits there until this consumer is upgraded.
     */
    public void requireReadableSchema() {
        if (schemaVersion > SCHEMA_VERSION) {
            throw new IllegalArgumentException("Order event schema version %d is newer than supported version %d"
                    .formatted(schemaVersion, SCHEMA_VERSION));
        }
    }
}
