package dev.swirlit.demo.order.config;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import dev.swirlit.demo.order.repository.ProcessedEventRepository;
import dev.swirlit.demo.order.service.EventRetention;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** The retention job takes a database lock, so only one replica runs it at a time. */
@SpringBootTest(properties = {
        "app.messaging.enabled=true",
        "app.messaging.outbox.poll-interval=1h",
        // The lock table is not a JPA entity, so this test creates the development schema in its own database.
        "spring.sql.init.mode=always",
        "spring.datasource.url=jdbc:h2:mem:order-lock-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class SchedulerLockTest {

    private static final String JOB = "order-event-retention";

    @Autowired
    private LockProvider lockProvider;

    @Autowired
    private EventRetention eventRetention;

    @MockitoSpyBean
    private ProcessedEventRepository processedEventRepository;

    @Test
    void aLockIsHeldByOneOwnerUntilItIsReleased() {
        Optional<SimpleLock> first = lockProvider.lock(lock("lock-test"));

        assertThat(first).isPresent();
        assertThat(lockProvider.lock(lock("lock-test"))).isEmpty();

        first.get().unlock();
        Optional<SimpleLock> again = lockProvider.lock(lock("lock-test"));
        assertThat(again).isPresent();
        again.get().unlock();
    }

    @Test
    void theRetentionJobSkipsARunWhileAnotherReplicaHoldsItsLock() {
        SimpleLock otherReplica = lockProvider.lock(lock(JOB)).orElseThrow();

        eventRetention.purgeExpired();
        verify(processedEventRepository, never()).deleteProcessedBefore(any());

        otherReplica.unlock();
        eventRetention.purgeExpired();
        verify(processedEventRepository).deleteProcessedBefore(any());
    }

    private static LockConfiguration lock(String name) {
        return new LockConfiguration(Instant.now(), name, Duration.ofMinutes(5), Duration.ZERO);
    }
}
