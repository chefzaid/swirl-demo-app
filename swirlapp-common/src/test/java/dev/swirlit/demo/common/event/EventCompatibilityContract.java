package dev.swirlit.demo.common.event;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import dev.swirlit.demo.common.domain.OrderStatus;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Deserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.kafka.support.serializer.SerializationUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads and writes order events with a service's own Kafka serializer settings. Each service extends it as a
 * Spring Boot test, so a configuration change that breaks older or newer producers fails that service's build.
 */
public abstract class EventCompatibilityContract {

    private static final String TOPIC = "order_topic";

    @Autowired
    protected KafkaProperties kafkaProperties;

    @Test
    void writesThePayloadWithoutAJavaTypeHeader() {
        try (JacksonJsonSerializer<Object> serializer = new JacksonJsonSerializer<>()) {
            serializer.configure(kafkaProperties.buildProducerProperties(), false);
            RecordHeaders headers = new RecordHeaders();

            String json = new String(serializer.serialize(TOPIC, headers,
                    new OrderEvent(7L, 2L, 1001L, null, OrderStatus.PENDING, Instant.EPOCH)), StandardCharsets.UTF_8);

            assertThat(headers.toArray()).isEmpty();
            assertThat(json).contains("\"schemaVersion\":1", "\"orderId\":7");
        }
    }

    @Test
    void readsEventsFromOlderProducers() {
        OrderEvent event = read("""
                {"orderId":7,"userId":2,"productId":1001,"status":"PENDING","occurredAt":"2026-10-14T10:00:00Z"}""",
                new RecordHeaders().add("__TypeId__", "dev.swirlit.demo.old.OrderCreated".getBytes(StandardCharsets.UTF_8)));

        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.orderId()).isEqualTo(7L);
        assertThat(event.userName()).isNull();
    }

    @Test
    void ignoresFieldsAddedByNewerProducersOfTheSameVersion() {
        OrderEvent event = read("""
                {"schemaVersion":1,"orderId":7,"userId":2,"productId":1001,"status":"PENDING",
                 "occurredAt":"2026-10-14T10:00:00Z","quantity":3,"channel":{"name":"web"}}""", new RecordHeaders());

        assertThat(event).isEqualTo(new OrderEvent(7L, 2L, 1001L, null, OrderStatus.PENDING,
                Instant.parse("2026-10-14T10:00:00Z")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"orderId\":7,\"status\":\"CANCELLED\"}",
            "{\"orderId\":",
            "not json"})
    void handsUnreadableRecordsToTheErrorHandlerInsteadOfFailingThePoll(String payload) {
        RecordHeaders headers = new RecordHeaders();

        assertThat(read(payload, headers)).isNull();
        assertThat(headers.lastHeader(SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER)).isNotNull();
    }

    private OrderEvent read(String json, Headers headers) {
        try (Deserializer<Object> deserializer = configuredDeserializer()) {
            return (OrderEvent) deserializer.deserialize(TOPIC, headers, json.getBytes(StandardCharsets.UTF_8));
        }
    }

    @SuppressWarnings("unchecked")
    private Deserializer<Object> configuredDeserializer() {
        Map<String, Object> properties = kafkaProperties.buildConsumerProperties();
        Deserializer<Object> deserializer = BeanUtils.instantiateClass(
                (Class<Deserializer<Object>>) properties.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG));
        deserializer.configure(properties, false);
        return deserializer;
    }
}
