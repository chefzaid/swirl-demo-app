package dev.swirlit.demo.order.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import dev.swirlit.demo.common.domain.OrderStatus;
import dev.swirlit.demo.common.event.OrderEvent;
import dev.swirlit.demo.order.domain.OutboxEvent;
import dev.swirlit.demo.order.repository.OutboxEventRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<Object, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OutboxRelay relay = new OutboxRelay(repository, kafkaTemplate, JSON, inlineTransactions(),
            registry, 50, Duration.ofDays(7), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void publishesPendingEventsAndMarksThemPublished() {
        OutboxEvent first = outboxEvent(7L);
        OutboxEvent second = outboxEvent(8L);
        when(repository.lockUnpublished(Limit.of(50))).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(eq("order_topic"), any(), any())).thenReturn(acknowledged());

        assertThat(relay.publishPending()).isEqualTo(2);

        verify(kafkaTemplate).send("order_topic", "7", event(7L));
        verify(kafkaTemplate).send("order_topic", "8", event(8L));
        assertThat(first.getPublishedAt()).isEqualTo(NOW);
        assertThat(second.getPublishedAt()).isEqualTo(NOW);
        assertThat(registry.counter("swirl.outbox.published").count()).isEqualTo(2);
    }

    @Test
    void keepsEventsPendingWhenKafkaRejectsThem() {
        OutboxEvent rejected = outboxEvent(7L);
        OutboxEvent accepted = outboxEvent(8L);
        when(repository.lockUnpublished(Limit.of(50))).thenReturn(List.of(rejected, accepted));
        when(kafkaTemplate.send("order_topic", "7", event(7L)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        when(kafkaTemplate.send("order_topic", "8", event(8L))).thenReturn(acknowledged());

        assertThat(relay.publishPending()).isEqualTo(1);

        assertThat(rejected.getPublishedAt()).isNull();
        assertThat(rejected.getAttempts()).isEqualTo(1);
        assertThat(accepted.getPublishedAt()).isEqualTo(NOW);
        assertThat(registry.counter("swirl.outbox.failed").count()).isEqualTo(1);
    }

    @Test
    void anUnreadablePayloadStaysPendingInsteadOfBlockingTheBatch() {
        OutboxEvent corrupt = new OutboxEvent("order_topic", "9", "{not json", NOW);
        OutboxEvent valid = outboxEvent(10L);
        when(repository.lockUnpublished(Limit.of(50))).thenReturn(List.of(corrupt, valid));
        when(kafkaTemplate.send("order_topic", "10", event(10L))).thenReturn(acknowledged());

        assertThat(relay.publishPending()).isEqualTo(1);
        assertThat(corrupt.getPublishedAt()).isNull();
    }

    @Test
    void deletesPublishedEventsOlderThanTheRetention() {
        when(repository.deletePublishedBefore(NOW.minus(Duration.ofDays(7)))).thenReturn(3);

        assertThat(relay.deletePublished()).isEqualTo(3);
    }

    @Test
    void exportsThePendingBacklog() {
        when(repository.countByPublishedAtIsNull()).thenReturn(4L);

        assertThat(registry.get("swirl.outbox.pending").gauge().value()).isEqualTo(4);
    }

    private static OutboxEvent outboxEvent(long orderId) {
        return new OutboxEvent("order_topic", Long.toString(orderId), JSON.writeValueAsString(event(orderId)), NOW);
    }

    private static OrderEvent event(long orderId) {
        return new OrderEvent(orderId, 1L, 2501L, null, OrderStatus.PENDING, NOW);
    }

    private static CompletableFuture<SendResult<Object, Object>> acknowledged() {
        return CompletableFuture.completedFuture(null);
    }

    private static TransactionTemplate inlineTransactions() {
        return new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }
}
