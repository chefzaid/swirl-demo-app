package dev.swirlit.demo.common.messaging;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessagingHealthIndicatorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void disabledMessagingDoesNotAffectHealth() {
        var indicator = new MessagingHealthIndicator(false, true, () -> {
            throw new AssertionError("no broker call expected");
        }, CLOCK);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void reachableBrokerIsUp() {
        Admin admin = adminAnswering(KafkaFuture.completedFuture("cluster-1"));

        var health = new MessagingHealthIndicator(true, false, () -> admin, CLOCK).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("required", false);
    }

    @Test
    void optionalMessagingDegradesWithoutFailingReadiness() {
        Admin admin = adminAnswering(failed());

        var health = new MessagingHealthIndicator(true, false, () -> admin, CLOCK).health();

        assertThat(health.getStatus()).isEqualTo(MessagingHealthIndicator.DEGRADED);
        assertThat(health.getDetails()).containsEntry("error", "TimeoutException");
    }

    @Test
    void requiredMessagingIsDownWhenTheBrokerIsUnreachable() {
        Admin admin = adminAnswering(failed());

        assertThat(new MessagingHealthIndicator(true, true, () -> admin, CLOCK).health().getStatus())
                .isEqualTo(Status.DOWN);
    }

    @Test
    void anAdminClientThatCannotBeCreatedCountsAsUnreachable() {
        var indicator = new MessagingHealthIndicator(true, true, () -> {
            throw new IllegalStateException("no resolvable bootstrap urls");
        }, CLOCK);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void healthCallsReuseTheLastResultBetweenRefreshes() throws Exception {
        Admin admin = adminAnswering(KafkaFuture.completedFuture("cluster-1"));
        var created = new AtomicInteger();
        var indicator = new MessagingHealthIndicator(true, false, () -> {
            created.incrementAndGet();
            return admin;
        }, CLOCK);

        indicator.health();
        indicator.health();
        indicator.destroy();

        assertThat(created).hasValue(1);
        verify(admin, times(1)).describeCluster(any(DescribeClusterOptions.class));
        verify(admin).close(any());
    }

    @Test
    void pendingChecksKeepThePreviousResult() {
        Admin admin = adminAnswering(new KafkaFutureImpl<>());

        assertThat(new MessagingHealthIndicator(true, true, () -> admin, CLOCK).health().getStatus())
                .isEqualTo(Status.UNKNOWN);
    }

    private static Admin adminAnswering(KafkaFuture<String> clusterId) {
        Admin admin = mock(Admin.class);
        DescribeClusterResult result = mock(DescribeClusterResult.class);
        when(result.clusterId()).thenReturn(clusterId);
        when(admin.describeCluster(any(DescribeClusterOptions.class))).thenReturn(result);
        return admin;
    }

    private static KafkaFuture<String> failed() {
        var future = new KafkaFutureImpl<String>();
        future.completeExceptionally(new TimeoutException("timed out"));
        return future;
    }
}
