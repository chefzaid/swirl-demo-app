package dev.swirlit.demo.common.config;

import java.util.LinkedHashMap;
import java.util.Map;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.KafkaAdmin.NewTopics;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.util.backoff.FixedBackOff;
import org.apache.kafka.common.TopicPartition;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.messaging.enabled", havingValue = "true")
public class KafkaReliabilityConfig {

    @Bean
    NewTopics applicationTopics(
            @Value("${app.messaging.topics.order:order_topic}") String orderTopic,
            @Value("${app.messaging.topics.result:order_result_topic}") String resultTopic) {
        return new NewTopics(
                TopicBuilder.name(orderTopic).partitions(3).build(),
                TopicBuilder.name(resultTopic).partitions(3).build(),
                TopicBuilder.name(orderTopic + ".DLT").partitions(3).build(),
                TopicBuilder.name(resultTopic + ".DLT").partitions(3).build());
    }

    @Bean
    DefaultErrorHandler kafkaErrorHandler(
            KafkaTemplate<Object, Object> kafkaTemplate,
            @Value("${app.messaging.retry.interval:1s}") java.time.Duration retryInterval,
            @Value("${app.messaging.retry.max-attempts:4}") long maxAttempts,
            ObjectProvider<MeterRegistry> meterRegistry) {
        KafkaTemplate<Object, Object> rawTemplate = new KafkaTemplate<>(kafkaTemplate.getProducerFactory(),
                Map.of(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class));
        DefaultErrorHandler handler = new DefaultErrorHandler(
                deadLetterRecoverer(kafkaTemplate, rawTemplate),
                new FixedBackOff(retryInterval.toMillis(), Math.max(0, maxAttempts - 1)));
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        handler.setRetryListeners(new DeliveryMetrics(meterRegistry.getIfAvailable(SimpleMeterRegistry::new)));
        return handler;
    }

    /**
     * Sends exhausted or rejected records to {@code <topic>.DLT}. A record the consumer could not deserialize
     * reaches the recoverer as its original bytes, which go out unchanged through {@code rawTemplate} so the
     * dead-letter topic holds exactly what was received; every other value is written as JSON.
     */
    static DeadLetterPublishingRecoverer deadLetterRecoverer(
            KafkaOperations<Object, Object> jsonTemplate, KafkaOperations<Object, Object> rawTemplate) {
        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, rawTemplate);
        templates.put(Object.class, jsonTemplate);
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(templates,
                (consumerRecord, exception) -> new TopicPartition(consumerRecord.topic() + ".DLT", -1));
        recoverer.setFailIfSendResultIsError(true);
        return recoverer;
    }

    /** Counts failed deliveries and records sent to the dead-letter topic, tagged by source topic. */
    record DeliveryMetrics(MeterRegistry registry) implements RetryListener {

        @Override
        public void failedDelivery(ConsumerRecord<?, ?> consumerRecord, Exception exception, int deliveryAttempt) {
            count("swirl.events.failed_deliveries", "Listener deliveries that threw", consumerRecord);
        }

        @Override
        public void recovered(ConsumerRecord<?, ?> consumerRecord, Exception exception) {
            count("swirl.events.dead_lettered", "Records published to the dead-letter topic", consumerRecord);
        }

        private void count(String name, String description, ConsumerRecord<?, ?> consumerRecord) {
            Counter.builder(name).description(description).tag("topic", consumerRecord.topic())
                    .register(registry).increment();
        }
    }
}
