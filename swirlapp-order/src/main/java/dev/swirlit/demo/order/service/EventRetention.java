package dev.swirlit.demo.order.service;

import java.time.Clock;
import java.time.Duration;

import dev.swirlit.demo.order.repository.OutboxEventRepository;
import dev.swirlit.demo.order.repository.ProcessedEventRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

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
 *
 * <p>The job runs on one replica at a time through a scheduler lock, deletes by age so a repeated or overlapping
 * run removes nothing twice, and in dry-run mode only counts and logs what it would delete.
 */
@Component
@ConditionalOnProperty(name = "app.messaging.enabled", havingValue = "true")
public class EventRetention {

    private static final Logger log = LoggerFactory.getLogger(EventRetention.class);

    private final OutboxEventRepository outboxEventRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final TransactionTemplate transactionTemplate;
    private final Duration retention;
    private final boolean dryRun;
    private final Clock clock;

    @Autowired
    public EventRetention(
            OutboxEventRepository outboxEventRepository,
            ProcessedEventRepository processedEventRepository,
            TransactionTemplate transactionTemplate,
            @Value("${app.messaging.event-retention:7d}") Duration retention,
            @Value("${app.messaging.event-retention-dry-run:false}") boolean dryRun) {
        this(outboxEventRepository, processedEventRepository, transactionTemplate, retention, dryRun,
                Clock.systemUTC());
    }

    EventRetention(OutboxEventRepository outboxEventRepository, ProcessedEventRepository processedEventRepository,
            TransactionTemplate transactionTemplate, Duration retention, boolean dryRun, Clock clock) {
        this.outboxEventRepository = outboxEventRepository;
        this.processedEventRepository = processedEventRepository;
        this.transactionTemplate = transactionTemplate;
        this.retention = retention;
        this.dryRun = dryRun;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.messaging.event-retention-cron:0 17 3 * * *}")
    @SchedulerLock(name = "order-event-retention", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void purgeExpired() {
        var cutoff = clock.instant().minus(retention);
        if (dryRun) {
            log.info("Dry run: would purge {} published outbox events and {} inbox entries older than {}",
                    outboxEventRepository.countByPublishedAtBefore(cutoff),
                    processedEventRepository.countByProcessedAtBefore(cutoff), cutoff);
            return;
        }
        transactionTemplate.executeWithoutResult(status -> log.info(
                "Purged {} published outbox events and {} inbox entries older than {}",
                outboxEventRepository.deletePublishedBefore(cutoff),
                processedEventRepository.deleteProcessedBefore(cutoff), cutoff));
    }
}
