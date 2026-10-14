package dev.swirlit.demo.order.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import dev.swirlit.demo.order.repository.OutboxEventRepository;
import dev.swirlit.demo.order.repository.ProcessedEventRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class EventRetentionTest {

    private static final Instant NOW = Instant.parse("2026-10-14T03:17:00Z");
    private static final Instant CUTOFF = Instant.parse("2026-10-07T03:17:00Z");

    private final OutboxEventRepository outbox = mock();
    private final ProcessedEventRepository inbox = mock();
    private final TransactionTemplate transactions = new TransactionTemplate() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(new SimpleTransactionStatus());
        }
    };

    @Test
    void purgesPublishedOutboxRowsAndInboxEntriesOlderThanTheRetention() {
        retention(false).purgeExpired();

        verify(outbox).deletePublishedBefore(CUTOFF);
        verify(inbox).deleteProcessedBefore(CUTOFF);
    }

    @Test
    void aDryRunCountsWhatItWouldPurgeAndDeletesNothing(CapturedOutput output) {
        when(outbox.countByPublishedAtBefore(CUTOFF)).thenReturn(12L);
        when(inbox.countByProcessedAtBefore(CUTOFF)).thenReturn(5L);

        retention(true).purgeExpired();

        verify(outbox, never()).deletePublishedBefore(any());
        verify(inbox, never()).deleteProcessedBefore(any());
        assertThat(output).contains(
                "Dry run: would purge 12 published outbox events and 5 inbox entries older than " + CUTOFF);
    }

    private EventRetention retention(boolean dryRun) {
        return new EventRetention(outbox, inbox, transactions, Duration.ofDays(7), dryRun,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
