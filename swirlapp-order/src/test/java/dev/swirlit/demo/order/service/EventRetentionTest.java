package dev.swirlit.demo.order.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import dev.swirlit.demo.order.repository.OutboxEventRepository;
import dev.swirlit.demo.order.repository.ProcessedEventRepository;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EventRetentionTest {

    @Test
    void purgesPublishedOutboxRowsAndInboxEntriesOlderThanTheRetention() {
        Instant now = Instant.parse("2026-10-14T03:17:00Z");
        var outbox = mock(OutboxEventRepository.class);
        var inbox = mock(ProcessedEventRepository.class);
        var transactions = new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };

        new EventRetention(outbox, inbox, transactions, Duration.ofDays(7), Clock.fixed(now, ZoneOffset.UTC))
                .purgeExpired();

        verify(outbox).deletePublishedBefore(Instant.parse("2026-10-07T03:17:00Z"));
        verify(inbox).deleteProcessedBefore(Instant.parse("2026-10-07T03:17:00Z"));
    }
}
