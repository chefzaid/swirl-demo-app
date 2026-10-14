package dev.swirlit.demo.order.service;

import java.time.Clock;
import java.time.Duration;

import dev.swirlit.demo.order.repository.OutboxEventRepository;
import dev.swirlit.demo.order.repository.ProcessedEventRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes published outbox rows and inbox entries older than the retention. The retention must exceed the
 * longest time a duplicate can still arrive (topic retention plus consumer downtime), or the inbox forgets it.
 */
@Component
@ConditionalOnProperty(name = "app.messaging.enabled", havingValue = "true")
public class EventRetention {

    private static final Logger log = LoggerFactory.getLogger(EventRetention.class);

    private final OutboxEventRepository outboxEventRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final TransactionTemplate transactionTemplate;
    private final Duration retention;
    private final Clock clock;

    @Autowired
    public EventRetention(
            OutboxEventRepository outboxEventRepository,
            ProcessedEventRepository processedEventRepository,
            TransactionTemplate transactionTemplate,
            @Value("${app.messaging.event-retention:7d}") Duration retention) {
        this(outboxEventRepository, processedEventRepository, transactionTemplate, retention, Clock.systemUTC());
    }

    EventRetention(OutboxEventRepository outboxEventRepository, ProcessedEventRepository processedEventRepository,
            TransactionTemplate transactionTemplate, Duration retention, Clock clock) {
        this.outboxEventRepository = outboxEventRepository;
        this.processedEventRepository = processedEventRepository;
        this.transactionTemplate = transactionTemplate;
        this.retention = retention;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.messaging.event-retention-cron:0 17 3 * * *}")
    public void purgeExpired() {
        var cutoff = clock.instant().minus(retention);
        transactionTemplate.executeWithoutResult(status -> log.info(
                "Purged {} published outbox events and {} inbox entries older than {}",
                outboxEventRepository.deletePublishedBefore(cutoff),
                processedEventRepository.deleteProcessedBefore(cutoff), cutoff));
    }
}
