package dev.swirlit.demo.common.messaging;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

/**
 * Reports whether the Kafka cluster answers, and whether the service can work without it.
 *
 * <p>Messaging is optional by default: an unreachable broker reports {@code DEGRADED}, which keeps the pod
 * ready because orders are still accepted and stay {@code PENDING} until events flow again. With
 * {@code app.messaging.required=true} it reports {@code DOWN}, and the readiness group takes the pod out
 * of service. The broker is queried in the background; a health call returns the last result, so probes
 * never wait on the network.
 */
@Component("messaging")
public class MessagingHealthIndicator implements HealthIndicator, DisposableBean {

    public static final Status DEGRADED = new Status("DEGRADED", "Kafka is unreachable; events are delayed");

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REFRESH = Duration.ofSeconds(10);

    private final boolean enabled;
    private final boolean required;
    private final Supplier<Admin> adminFactory;
    private final Clock clock;
    private final AtomicBoolean checking = new AtomicBoolean();
    private Admin admin;
    private volatile Instant lastCheck = Instant.EPOCH;
    private volatile Health last;

    @Autowired
    public MessagingHealthIndicator(
            KafkaAdmin kafkaAdmin,
            @Value("${app.messaging.enabled:false}") boolean enabled,
            @Value("${app.messaging.required:false}") boolean required) {
        this(enabled, required, () -> Admin.create(adminConfig(kafkaAdmin.getConfigurationProperties())),
                Clock.systemUTC());
    }

    MessagingHealthIndicator(boolean enabled, boolean required, Supplier<Admin> adminFactory, Clock clock) {
        this.enabled = enabled;
        this.required = required;
        this.adminFactory = adminFactory;
        this.clock = clock;
        this.last = Health.unknown().withDetail("required", required).build();
    }

    @Override
    public Health health() {
        if (!enabled) {
            return Health.unknown().withDetail("enabled", false).build();
        }
        Instant now = clock.instant();
        if (!now.isBefore(lastCheck.plus(REFRESH)) && checking.compareAndSet(false, true)) {
            lastCheck = now;
            try {
                admin().describeCluster(new DescribeClusterOptions().timeoutMs((int) TIMEOUT.toMillis()))
                        .clusterId().whenComplete((clusterId, error) -> {
                            last = error == null ? reachable() : unreachable(error);
                            checking.set(false);
                        });
            } catch (RuntimeException exception) {
                last = unreachable(exception);
                checking.set(false);
            }
        }
        return last;
    }

    @Override
    public synchronized void destroy() {
        if (admin != null) {
            admin.close(TIMEOUT);
        }
    }

    private synchronized Admin admin() {
        if (admin == null) {
            admin = adminFactory.get();
        }
        return admin;
    }

    private Health reachable() {
        return Health.up().withDetail("required", required).build();
    }

    private Health unreachable(Throwable error) {
        return Health.status(required ? Status.DOWN : DEGRADED)
                .withDetail("required", required)
                .withDetail("error", error.getClass().getSimpleName())
                .build();
    }

    private static Map<String, Object> adminConfig(Map<String, Object> base) {
        Map<String, Object> config = new HashMap<>(base);
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) TIMEOUT.toMillis());
        config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) TIMEOUT.toMillis());
        return config;
    }
}
