package dev.swirlit.demo.common.config;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class KafkaReliabilityConfigTest {
    @Test
    void topicsAndDeadLettersStayInTheSelectedEnvironment() {
        var topics = new KafkaReliabilityConfig().applicationTopics("swirl-demo-app.int.order_topic", "swirl-demo-app.int.order_result_topic");
        Collection<NewTopic> declared = ReflectionTestUtils.invokeMethod(topics, "getNewTopics");
        assertEquals(Set.of("swirl-demo-app.int.order_topic", "swirl-demo-app.int.order_result_topic",
                "swirl-demo-app.int.order_topic.DLT", "swirl-demo-app.int.order_result_topic.DLT"),
                declared.stream().map(NewTopic::name).collect(Collectors.toSet()));
    }

    @Test
    void countsFailedDeliveriesAndDeadLettersPerTopic() {
        var registry = new SimpleMeterRegistry();
        var metrics = new KafkaReliabilityConfig.DeliveryMetrics(registry);
        var consumerRecord = new ConsumerRecord<>("order_topic", 0, 7L, "key", "value");
        var failure = new IllegalStateException("handler failed");

        metrics.failedDelivery(consumerRecord, failure, 1);
        metrics.failedDelivery(consumerRecord, failure, 2);
        metrics.recovered(consumerRecord, failure);

        assertEquals(2.0, registry.counter("swirl.events.failed_deliveries", "topic", "order_topic").count());
        assertEquals(1.0, registry.counter("swirl.events.dead_lettered", "topic", "order_topic").count());
    }

    @Test
    void deadLettersUnreadableRecordsAsTheirOriginalBytes() {
        KafkaOperations<Object, Object> jsonTemplate = mock();
        KafkaOperations<Object, Object> rawTemplate = mock();
        when(rawTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        byte[] payload = "{\"orderId\":".getBytes(StandardCharsets.UTF_8);
        RecordHeaders headers = new RecordHeaders();
        try (var deserializer = new ErrorHandlingDeserializer<>(new JacksonJsonDeserializer<>(Object.class))) {
            assertNull(deserializer.deserialize("order_topic", headers, payload));
        }
        var unreadable = new ConsumerRecord<Object, Object>("order_topic", 1, 7L, -1L, TimestampType.CREATE_TIME,
                -1, -1, "key", null, headers, Optional.empty());

        KafkaReliabilityConfig.deadLetterRecoverer(jsonTemplate, rawTemplate)
                .accept(unreadable, new ListenerExecutionFailedException("unreadable",
                        new DeserializationException("bad payload", payload, false, null)));

        ArgumentCaptor<ProducerRecord<Object, Object>> sent = ArgumentCaptor.captor();
        verify(rawTemplate).send(sent.capture());
        verify(jsonTemplate, never()).send(any(ProducerRecord.class));
        assertEquals("order_topic.DLT", sent.getValue().topic());
        assertArrayEquals(payload, (byte[]) sent.getValue().value());
    }
}
