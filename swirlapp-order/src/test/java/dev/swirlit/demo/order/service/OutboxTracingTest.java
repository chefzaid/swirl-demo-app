package dev.swirlit.demo.order.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import dev.swirlit.demo.common.domain.OrderStatus;
import dev.swirlit.demo.common.event.OrderEvent;
import dev.swirlit.demo.order.repository.OutboxEventRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.Serializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.mock.MockProducerFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** An order change's trace survives the outbox: the relay's record continues it rather than the poll's trace. */
@SpringBootTest
@ActiveProfiles("test")
class OutboxTracingTest {

    @Autowired
    private Tracer tracer;

    @Autowired
    private Propagator propagator;

    @Autowired
    private ObservationRegistry observationRegistry;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private OutboxEventRepository repository;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterEach
    void clearOutbox() {
        repository.deleteAll();
    }

    @Test
    void theRelayedRecordCarriesTheTraceOfTheRequestThatChangedTheOrder() {
        Serializer<Object> values = (topic, value) -> String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        var producer = new MockProducer<Object, Object>(true, null, values, values);
        var template = new KafkaTemplate<>(new MockProducerFactory<>(() -> producer));
        var admin = new KafkaAdmin(Map.of());
        admin.setClusterId("test-cluster");
        template.setKafkaAdmin(admin);
        template.setObservationEnabled(true);
        template.setApplicationContext(context);
        template.afterSingletonsInstantiated();
        var outbox = new OrderEventOutbox(repository, jsonMapper, true, "order_topic", Clock.systemUTC(),
                tracer, propagator);
        var relay = new OutboxRelay(repository, template, jsonMapper, transactionTemplate, new SimpleMeterRegistry(),
                10, Clock.systemUTC(), tracer, propagator);

        Span request = tracer.nextSpan().name("PUT /api/orders/7").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(request)) {
            transactionTemplate.executeWithoutResult(status -> outbox.enqueue(
                    new OrderEvent(7L, 2L, 2001L, null, OrderStatus.PENDING, Instant.now())));
        } finally {
            request.end();
        }
        // The scheduler runs each poll in its own observation, which starts a trace of its own.
        Observation.createNotStarted("tasks.scheduled.execution", observationRegistry).observe(relay::publishPending);

        assertThat(producer.history()).hasSize(1);
        String traceparent = new String(
                producer.history().getFirst().headers().lastHeader("traceparent").value(), StandardCharsets.UTF_8);
        assertThat(traceparent).contains(request.context().traceId());
    }
}
