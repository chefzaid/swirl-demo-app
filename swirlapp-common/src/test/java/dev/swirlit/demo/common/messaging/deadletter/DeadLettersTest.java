package dev.swirlit.demo.common.messaging.deadletter;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import dev.swirlit.demo.common.audit.AuditTrail;
import dev.swirlit.demo.common.event.EventIds;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.support.KafkaHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeadLettersTest {

    private static final String ORDERS_DLT = "order_topic.DLT";
    private static final String RESULTS_DLT = "order_result_topic.DLT";
    private static final TopicPartition ORDERS_0 = new TopicPartition(ORDERS_DLT, 0);
    private static final TopicPartition RESULTS_0 = new TopicPartition(RESULTS_DLT, 0);

    private final MockConsumer<byte[], byte[]> consumer = new MockConsumer<>("earliest");
    private final KafkaOperations<byte[], byte[]> producer = mock();
    private final AuditTrail auditTrail = mock();
    private DeadLetters deadLetters;

    @BeforeEach
    void setUp() {
        Node node = new Node(0, "localhost", 9092);
        consumer.updatePartitions(ORDERS_DLT, List.of(new PartitionInfo(ORDERS_DLT, 0, node, null, null)));
        consumer.updatePartitions(RESULTS_DLT, List.of(new PartitionInfo(RESULTS_DLT, 0, node, null, null)));
        consumer.updateBeginningOffsets(Map.of(ORDERS_0, 0L, RESULTS_0, 0L));
        consumer.updateEndOffsets(Map.of(ORDERS_0, 3L, RESULTS_0, 1L));
        consumer.schedulePollTask(() -> List.of(
                        record(ORDERS_DLT, 1, 1_000, "order_topic", "order-outbox-1"),
                        record(ORDERS_DLT, 2, 3_000, "order_topic", "order-outbox-2"),
                        record(RESULTS_DLT, 0, 2_000, "order_result_topic", null)).stream()
                .filter(held -> consumer.assignment().contains(new TopicPartition(held.topic(), held.partition())))
                .forEach(consumer::addRecord));
        deadLetters = new DeadLetters(() -> consumer, producer, List.of("order_topic", "order_result_topic"),
                auditTrail);
    }

    @Test
    void listsTheNewestRecordsAcrossDeadLetterTopicsWithoutTheirPayload() {
        List<DeadLetter> newest = deadLetters.newest(2);

        assertThat(newest).extracting(DeadLetter::topic, DeadLetter::offset)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(ORDERS_DLT, 2L),
                        org.assertj.core.groups.Tuple.tuple(RESULTS_DLT, 0L));
        DeadLetter first = newest.getFirst();
        assertThat(first.originalTopic()).isEqualTo("order_topic");
        assertThat(first.key()).isEqualTo("42");
        assertThat(first.eventId()).isEqualTo("order-outbox-2");
        assertThat(first.exception()).isEqualTo("java.lang.IllegalArgumentException");
        assertThat(first.reason()).hasSize(303).endsWith("...");
        assertThat(first.sizeBytes()).isEqualTo(payload().length);
    }

    @Test
    void replaysTheOriginalBytesKeyAndEventIdToTheTopicTheRecordFailedOn() {
        when(producer.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        DeadLetter replayed = deadLetters.replay(ORDERS_DLT, 0, 1);

        ArgumentCaptor<ProducerRecord<byte[], byte[]>> sent = ArgumentCaptor.captor();
        verify(producer).send(sent.capture());
        ProducerRecord<byte[], byte[]> record = sent.getValue();
        assertThat(record.topic()).isEqualTo("order_topic");
        assertThat(record.key()).isEqualTo("42".getBytes(StandardCharsets.UTF_8));
        assertThat(record.value()).isEqualTo(payload());
        assertThat(EventIds.read(record.headers().lastHeader(EventIds.HEADER).value())).isEqualTo("order-outbox-1");
        assertThat(record.headers().lastHeader(DeadLetters.REPLAYED_FROM).value())
                .isEqualTo("order_topic.DLT-0@1".getBytes(StandardCharsets.UTF_8));
        assertThat(record.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_FQCN)).isNull();
        assertThat(replayed.offset()).isEqualTo(1L);
        verify(auditTrail).changed("dead-letter.replayed", "kafka-record", "order_topic.DLT-0@1");
    }

    @Test
    void refusesTopicsThatAreNotThisServicesDeadLetters() {
        assertThatThrownBy(() -> deadLetters.replay("order_topic", 0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Not a dead-letter topic of this service: order_topic");
        verify(producer, never()).send(any(ProducerRecord.class));
    }

    @Test
    void refusesOffsetsWithoutARecord() {
        assertThatThrownBy(() -> deadLetters.replay(ORDERS_DLT, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No record at order_topic.DLT-0@0");
    }

    @Test
    void refusesRecordsThatNameAnotherOriginalTopic() {
        MockConsumer<byte[], byte[]> foreign = new MockConsumer<>("earliest");
        foreign.schedulePollTask(() -> foreign.addRecord(record(ORDERS_DLT, 1, 1_000, "payments", null)));
        foreign.updateBeginningOffsets(Map.of(ORDERS_0, 0L));
        var service = new DeadLetters(() -> foreign, producer, List.of("order_topic"), auditTrail);

        assertThatThrownBy(() -> service.replay(ORDERS_DLT, 0, 1))
                .hasMessage("Record names an unknown original topic: payments");
    }

    private static ConsumerRecord<byte[], byte[]> record(String topic, long offset, long timestamp,
            String originalTopic, String eventId) {
        RecordHeaders headers = new RecordHeaders();
        headers.add(KafkaHeaders.DLT_ORIGINAL_TOPIC, originalTopic.getBytes(StandardCharsets.UTF_8));
        headers.add(KafkaHeaders.DLT_EXCEPTION_FQCN, "java.lang.IllegalArgumentException".getBytes(StandardCharsets.UTF_8));
        headers.add(KafkaHeaders.DLT_EXCEPTION_MESSAGE, "x".repeat(400).getBytes(StandardCharsets.UTF_8));
        EventIds.write(headers, eventId);
        return new ConsumerRecord<>(topic, 0, offset, timestamp, TimestampType.CREATE_TIME, 2, payload().length,
                "42".getBytes(StandardCharsets.UTF_8), payload(), headers, Optional.empty());
    }

    private static byte[] payload() {
        return "{\"orderId\":42}".getBytes(StandardCharsets.UTF_8);
    }
}
