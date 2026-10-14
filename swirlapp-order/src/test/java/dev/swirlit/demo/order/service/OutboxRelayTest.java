package dev.swirlit.demo.order.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import dev.swirlit.demo.common.domain.OrderStatus;
import dev.swirlit.demo.common.event.EventIds;
import dev.swirlit.demo.common.event.OrderEvent;
import dev.swirlit.demo.order.domain.OutboxEvent;
import dev.swirlit.demo.order.repository.OutboxEventRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
            registry, 50, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void publishesPendingEventsAndMarksThemPublished() {
        OutboxEvent first = outboxEvent(7L);
        OutboxEvent second = outboxEvent(8L);
        when(repository.lockUnpublished(Limit.of(50))).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(acknowledged());

        assertThat(relay.publishPending()).isEqualTo(2);

        ArgumentCaptor<ProducerRecord<Object, Object>> records = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate, times(2)).send(records.capture());
        assertThat(records.getAllValues()).extracting(ProducerRecord::topic, ProducerRecord::key, ProducerRecord::value)
                .containsExactly(tuple("order_topic", "7", event(7L)), tuple("order_topic", "8", event(8L)));
        assertThat(first.getPublishedAt()).isEqualTo(NOW);
        assertThat(second.getPublishedAt()).isEqualTo(NOW);
        assertThat(registry.counter("swirl.outbox.published").count()).isEqualTo(2);
    }

    @Test
    void keepsEventsPendingWhenKafkaRejectsThem() {
        OutboxEvent rejected = outboxEvent(7L);
        OutboxEvent accepted = outboxEvent(8L);
        when(repository.lockUnpublished(Limit.of(50))).thenReturn(List.of(rejected, accepted));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")))
                .thenReturn(acknowledged());

        assertThat(relay.publishPending()).isEqualTo(1);

        assertThat(rejected.getPublishedAt()).isNull();
        assertThat(rejected.getAttempts()).isEqualTo(1);
        assertThat(accepted.getPublishedAt()).isEqualTo(NOW);
        assertThat(registry.counter("swirl.outbox.failed").count()).isEqualTo(1);
    }

    @Test
    void anUnreadablePayloadStaysPendingInsteadOfBlockingTheBatch() {
        OutboxEvent corrupt = persisted(new OutboxEvent("order_topic", "9", "{not json", NOW), 9L);
        OutboxEvent valid = outboxEvent(10L);
        when(repository.lockUnpublished(Limit.of(50))).thenReturn(List.of(corrupt, valid));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(acknowledged());

        assertThat(relay.publishPending()).isEqualTo(1);
        assertThat(corrupt.getPublishedAt()).isNull();
    }

    @Test
    void eachRecordCarriesTheIdOfItsOutboxRowSoRepublishedCopiesShareIt() {
        OutboxEvent event = persisted(outboxEvent(7L), 41L);
        when(repository.lockUnpublished(Limit.of(50))).thenReturn(List.of(event));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(acknowledged());

        relay.publishPending();

        ArgumentCaptor<ProducerRecord<Object, Object>> record = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(record.capture());
        assertThat(new String(record.getValue().headers().lastHeader(EventIds.HEADER).value(), StandardCharsets.UTF_8))
                .isEqualTo("order-outbox-41");
    }

    @Test
    void exportsThePendingBacklog() {
        when(repository.countByPublishedAtIsNull()).thenReturn(4L);

        assertThat(registry.get("swirl.outbox.pending").gauge().value()).isEqualTo(4);
    }

    private static OutboxEvent outboxEvent(long orderId) {
        return persisted(new OutboxEvent("order_topic", Long.toString(orderId), JSON.writeValueAsString(event(orderId)),
                NOW), orderId);
    }

    private static OutboxEvent persisted(OutboxEvent event, long id) {
        ReflectionTestUtils.setField(event, "id", id);
        return event;
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
