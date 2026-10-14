package dev.swirlit.demo.common.messaging.deadletter;

import java.time.Instant;

/**
 * One record held in a dead-letter topic. The payload is left out on purpose: results carry user names, and
 * operators decide on a replay from the failure reason and the identifiers.
 *
 * @param topic the dead-letter topic
 * @param originalTopic the topic the record failed on, where a replay sends it
 * @param key the record key (the order ID)
 * @param eventId the {@code swirl-event-id} header, when the producer set one
 * @param exception the class of the final failure
 * @param reason the failure message, shortened
 * @param replayedFrom where an earlier replay took this record from, when it failed again after one
 */
public record DeadLetter(
        String topic,
        int partition,
        long offset,
        Instant timestamp,
        String originalTopic,
        String key,
        String eventId,
        String exception,
        String reason,
        String replayedFrom,
        int sizeBytes) {
}
