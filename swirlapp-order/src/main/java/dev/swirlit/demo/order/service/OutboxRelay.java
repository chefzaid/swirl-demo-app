package dev.swirlit.demo.order.service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import dev.swirlit.demo.common.event.EventIds;
import dev.swirlit.demo.common.event.OrderEvent;
import dev.swirlit.demo.order.domain.OutboxEvent;
import dev.swirlit.demo.order.repository.OutboxEventRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Polls the outbox and publishes pending events to Kafka (at least once, in outbox order per batch). Each batch
 * holds its rows locked until Kafka acknowledges them, and other instances skip locked rows, so several pods can
 * relay at once without publishing the same row concurrently. An event whose send fails stays pending and is
 * retried on the next poll. Each send continues the trace stored with its row, not the poll's own trace, so the
 * record carries the trace of the request that changed the order.
 */
@Component
@ConditionalOnProperty(name = "app.messaging.enabled", havingValue = "true")
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;
    private final Clock clock;
    private final Counter published;
    private final Counter failed;
    private final Tracer tracer;
    private final Propagator propagator;

    @Autowired
    public OutboxRelay(
            OutboxEventRepository outboxEventRepository,
            KafkaTemplate<Object, Object> kafkaTemplate,
            JsonMapper jsonMapper,
            TransactionTemplate transactionTemplate,
            MeterRegistry meterRegistry,
            ObjectProvider<Tracer> tracer,
            ObjectProvider<Propagator> propagator,
            @Value("${app.messaging.outbox.batch-size:100}") int batchSize) {
        this(outboxEventRepository, kafkaTemplate, jsonMapper, transactionTemplate, meterRegistry, batchSize,
                Clock.systemUTC(), tracer.getIfAvailable(() -> Tracer.NOOP),
                propagator.getIfAvailable(() -> Propagator.NOOP));
    }

    OutboxRelay(OutboxEventRepository outboxEventRepository, KafkaTemplate<Object, Object> kafkaTemplate,
            JsonMapper jsonMapper, TransactionTemplate transactionTemplate, MeterRegistry meterRegistry,
            int batchSize, Clock clock, Tracer tracer, Propagator propagator) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.jsonMapper = jsonMapper;
        this.transactionTemplate = transactionTemplate;
        this.batchSize = batchSize;
        this.clock = clock;
        this.tracer = tracer;
        this.propagator = propagator;
        this.published = Counter.builder("swirl.outbox.published").description("Outbox events published to Kafka")
                .register(meterRegistry);
        this.failed = Counter.builder("swirl.outbox.failed").description("Outbox publication attempts that failed")
                .register(meterRegistry);
        Gauge.builder("swirl.outbox.pending", outboxEventRepository, OutboxEventRepository::countByPublishedAtIsNull)
                .description("Outbox events not yet published").register(meterRegistry);
    }

    /** Publishes one batch per poll; returns how many events Kafka acknowledged. */
    @Scheduled(fixedDelayString = "${app.messaging.outbox.poll-interval:1s}")
    public int publishPending() {
        Integer count = transactionTemplate.execute(status -> publish(
                outboxEventRepository.lockUnpublished(Limit.of(batchSize))));
        return count == null ? 0 : count;
    }

    private int publish(List<OutboxEvent> batch) {
        List<CompletableFuture<SendResult<Object, Object>>> sends = new ArrayList<>(batch.size());
        for (OutboxEvent event : batch) {
            sends.add(send(event));
        }
        int acknowledged = 0;
        for (int index = 0; index < batch.size(); index++) {
            OutboxEvent event = batch.get(index);
            if (await(event, sends.get(index))) {
                event.markPublished(clock.instant());
                published.increment();
                acknowledged++;
            } else {
                event.recordFailedAttempt();
                failed.increment();
            }
        }
        return acknowledged;
    }

    private CompletableFuture<SendResult<Object, Object>> send(OutboxEvent event) {
        if (event.getTraceParent() == null) {
            return sendRecord(event);
        }
        Span span = propagator.extract(Map.of(OrderEventOutbox.TRACEPARENT, event.getTraceParent()), Map::get)
                .name("order.outbox.publish").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            return sendRecord(event);
        } finally {
            span.end();
        }
    }

    private CompletableFuture<SendResult<Object, Object>> sendRecord(OutboxEvent event) {
        try {
            OrderEvent payload = jsonMapper.readValue(event.getPayload(), OrderEvent.class);
            ProducerRecord<Object, Object> record = new ProducerRecord<>(event.getTopic(), event.getKey(), payload);
            EventIds.write(record.headers(), EventIds.ofOutboxRow(event.getId()));
            return kafkaTemplate.send(record);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private static boolean await(OutboxEvent event, CompletableFuture<SendResult<Object, Object>> send) {
        try {
            send.get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException exception) {
            Throwable cause = exception instanceof ExecutionException ? exception.getCause() : exception;
            log.warn("Could not publish outbox event id={} attempt={}: {}",
                    event.getId(), event.getAttempts() + 1, cause.getClass().getName());
            return false;
        }
    }
}
