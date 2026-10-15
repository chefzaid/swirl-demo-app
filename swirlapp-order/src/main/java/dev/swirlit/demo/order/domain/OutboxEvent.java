package dev.swirlit.demo.order.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** An event written in the same transaction as the order change it describes, awaiting publication. */
@Entity
@Table(name = "order_outbox")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String topic;

    @Column(name = "event_key", nullable = false)
    private String key;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant publishedAt;

    @Column(nullable = false)
    private int attempts;

    /** The W3C {@code traceparent} of the change that recorded the event, so its Kafka record joins that trace. */
    @Column(name = "trace_parent")
    private String traceParent;

    protected OutboxEvent() {
    }

    public OutboxEvent(String topic, String key, String payload, Instant createdAt) {
        this(topic, key, payload, createdAt, null);
    }

    public OutboxEvent(String topic, String key, String payload, Instant createdAt, String traceParent) {
        this.topic = topic;
        this.key = key;
        this.payload = payload;
        this.createdAt = createdAt;
        this.traceParent = traceParent;
    }

    public Long getId() {
        return id;
    }

    public String getTopic() {
        return topic;
    }

    public String getKey() {
        return key;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getTraceParent() {
        return traceParent;
    }

    public void markPublished(Instant when) {
        publishedAt = when;
        attempts++;
    }

    public void recordFailedAttempt() {
        attempts++;
    }
}
