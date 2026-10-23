package dev.swirlit.demo.order.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import dev.swirlit.demo.common.domain.OrderStatus;
import dev.swirlit.demo.common.event.OrderEvent;
import dev.swirlit.demo.order.domain.OutboxEvent;
import dev.swirlit.demo.order.repository.OutboxEventRepository;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class OrderEventOutboxTest {

    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);

    @Test
    void recordsTheEventForTheEnvironmentsTopicKeyedByOrder() {
        var outbox = new OrderEventOutbox(repository, JSON, true, "swirl-demo-app.uat.order_topic",
                Clock.fixed(NOW, ZoneOffset.UTC), Tracer.NOOP, Propagator.NOOP);
        var event = new OrderEvent(7L, 2L, 2001L, null, OrderStatus.PENDING, NOW);

        outbox.enqueue(event);

        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getTopic()).isEqualTo("swirl-demo-app.uat.order_topic");
        assertThat(saved.getValue().getKey()).isEqualTo("7");
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getValue().getPublishedAt()).isNull();
        assertThat(JSON.readValue(saved.getValue().getPayload(), OrderEvent.class)).isEqualTo(event);
    }

    @Test
    void recordsNothingWhenMessagingIsDisabled() {
        new OrderEventOutbox(repository, JSON, false, "order_topic", Clock.systemUTC(), Tracer.NOOP, Propagator.NOOP)
                .enqueue(new OrderEvent(7L, 2L, 2001L, null, OrderStatus.PENDING, NOW));

        verify(repository, never()).save(any());
    }
}
