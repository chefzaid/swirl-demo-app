package dev.swirlit.demo.common.config;

import java.util.Set;
import java.util.Collection;
import java.util.stream.Collectors;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
