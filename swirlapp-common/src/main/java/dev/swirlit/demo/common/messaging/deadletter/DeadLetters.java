package dev.swirlit.demo.common.messaging.deadletter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import dev.swirlit.demo.common.audit.AuditTrail;
import dev.swirlit.demo.common.event.EventIds;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.serializer.SerializationUtils;
import org.springframework.stereotype.Component;

/**
 * Reads and replays the records held in this service's dead-letter topics. A replay copies the record's
 * original bytes, key, and headers back to the topic it failed on, so its event ID survives and the consumer's
 * idempotency rules apply to it like to any redelivery. Reads use a short-lived consumer that is assigned
 * partitions directly and never commits, so the service's consumer group is untouched.
 */
@Component
@ConditionalOnProperty(name = "app.messaging.enabled", havingValue = "true")
public class DeadLetters {

    public static final String REPLAYED_FROM = "swirl-replayed-from";
    static final String SUFFIX = ".DLT";

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration POLL = Duration.ofMillis(200);
    private static final int REASON_LENGTH = 300;

    private final Supplier<Consumer<byte[], byte[]>> consumers;
    private final KafkaOperations<byte[], byte[]> producer;
    private final List<String> sourceTopics;
    private final AuditTrail auditTrail;

    @Autowired
    public DeadLetters(
            ConsumerFactory<Object, Object> consumerFactory,
            ProducerFactory<Object, Object> producerFactory,
            @Value("${spring.kafka.consumer.group-id}") String consumerGroup,
            @Value("${app.messaging.topics.order:order_topic}") String orderTopic,
            @Value("${app.messaging.topics.result:order_result_topic}") String resultTopic,
            AuditTrail auditTrail) {
        this(() -> rawConsumer(consumerFactory, consumerGroup), rawProducer(producerFactory),
                List.of(orderTopic, resultTopic), auditTrail);
    }

    DeadLetters(Supplier<Consumer<byte[], byte[]>> consumers, KafkaOperations<byte[], byte[]> producer,
            List<String> sourceTopics, AuditTrail auditTrail) {
        this.consumers = consumers;
        this.producer = producer;
        this.sourceTopics = List.copyOf(sourceTopics);
        this.auditTrail = auditTrail;
    }

    /** The newest {@code limit} records across this service's dead-letter topics, newest first. */
    public List<DeadLetter> newest(int limit) {
        List<DeadLetter> found = new ArrayList<>();
        try (Consumer<byte[], byte[]> consumer = consumers.get()) {
            List<TopicPartition> partitions = sourceTopics.stream()
                    .flatMap(topic -> consumer.partitionsFor(topic + SUFFIX).stream()
                            .map(info -> new TopicPartition(info.topic(), info.partition())))
                    .toList();
            consumer.assign(partitions);
            Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);
            Map<TopicPartition, Long> starts = consumer.beginningOffsets(partitions);
            partitions.forEach(partition -> consumer.seek(partition,
                    Math.max(starts.get(partition), ends.get(partition) - limit)));
            Instant deadline = Instant.now().plus(READ_TIMEOUT);
            while (partitions.stream().anyMatch(partition -> consumer.position(partition) < ends.get(partition))
                    && Instant.now().isBefore(deadline)) {
                consumer.poll(POLL).forEach(consumed -> found.add(describe(consumed)));
            }
        }
        return found.stream()
                .sorted(Comparator.comparing(DeadLetter::timestamp).reversed())
                .limit(limit)
                .toList();
    }

    /** Sends one dead-lettered record back to the topic it failed on and returns what was replayed. */
    public DeadLetter replay(String topic, int partition, long offset) {
        if (sourceTopics.stream().noneMatch(source -> (source + SUFFIX).equals(topic))) {
            throw new IllegalArgumentException("Not a dead-letter topic of this service: " + topic);
        }
        ConsumerRecord<byte[], byte[]> record = read(new TopicPartition(topic, partition), offset);
        DeadLetter letter = describe(record);
        if (!sourceTopics.contains(letter.originalTopic())) {
            throw new IllegalArgumentException("Record names an unknown original topic: " + letter.originalTopic());
        }
        ProducerRecord<byte[], byte[]> replay = new ProducerRecord<>(letter.originalTopic(), record.key(),
                record.value());
        for (Header header : record.headers()) {
            if (!header.key().startsWith(KafkaHeaders.PREFIX + "dlt-")
                    && !header.key().startsWith(SerializationUtils.DESERIALIZER_EXCEPTION_HEADER_PREFIX)
                    && !REPLAYED_FROM.equals(header.key())) {
                replay.headers().add(header);
            }
        }
        replay.headers().add(REPLAYED_FROM, position(record).getBytes(StandardCharsets.UTF_8));
        try {
            producer.send(replay).get(READ_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while replaying " + position(record), exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("Kafka did not accept the replay of " + position(record), exception);
        }
        auditTrail.changed("dead-letter.replayed", "kafka-record", position(record));
        return letter;
    }

    private ConsumerRecord<byte[], byte[]> read(TopicPartition partition, long offset) {
        try (Consumer<byte[], byte[]> consumer = consumers.get()) {
            consumer.assign(List.of(partition));
            consumer.seek(partition, offset);
            Instant deadline = Instant.now().plus(READ_TIMEOUT);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<byte[], byte[]> consumed : consumer.poll(POLL).records(partition)) {
                    if (consumed.offset() == offset) {
                        return consumed;
                    }
                    if (consumed.offset() > offset) {
                        break;
                    }
                }
                if (consumer.position(partition) > offset) {
                    break;
                }
            }
        }
        throw new IllegalArgumentException("No record at %s-%d@%d".formatted(partition.topic(),
                partition.partition(), offset));
    }

    private static DeadLetter describe(ConsumerRecord<byte[], byte[]> record) {
        Headers headers = record.headers();
        String original = text(headers, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        if (original == null && record.topic().endsWith(SUFFIX)) {
            original = record.topic().substring(0, record.topic().length() - SUFFIX.length());
        }
        String reason = text(headers, KafkaHeaders.DLT_EXCEPTION_MESSAGE);
        if (reason != null && reason.length() > REASON_LENGTH) {
            reason = reason.substring(0, REASON_LENGTH) + "...";
        }
        return new DeadLetter(record.topic(), record.partition(), record.offset(),
                Instant.ofEpochMilli(record.timestamp()), original,
                record.key() == null ? null : new String(record.key(), StandardCharsets.UTF_8),
                EventIds.read(bytes(headers, EventIds.HEADER)),
                text(headers, KafkaHeaders.DLT_EXCEPTION_FQCN), reason,
                text(headers, REPLAYED_FROM),
                record.value() == null ? 0 : record.value().length);
    }

    private static String position(ConsumerRecord<?, ?> record) {
        return "%s-%d@%d".formatted(record.topic(), record.partition(), record.offset());
    }

    private static byte[] bytes(Headers headers, String key) {
        Header header = headers.lastHeader(key);
        return header == null ? null : header.value();
    }

    private static String text(Headers headers, String key) {
        byte[] value = bytes(headers, key);
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Consumer<byte[], byte[]> rawConsumer(ConsumerFactory<Object, Object> factory, String group) {
        Properties overrides = new Properties();
        overrides.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        overrides.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        overrides.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // Metadata and offset lookups give up with the read instead of blocking an operator's request for 60 s.
        overrides.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) READ_TIMEOUT.toMillis());
        // The group is never joined or committed to; it only keeps the client inside the service's ACL prefix.
        return (Consumer) factory.createConsumer(group + ".dead-letters", null, null, overrides);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static KafkaOperations<byte[], byte[]> rawProducer(ProducerFactory<Object, Object> factory) {
        return (KafkaOperations) new KafkaTemplate<>(factory, Map.of(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class));
    }
}
