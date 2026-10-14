package dev.swirlit.demo.common.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

/**
 * Treats the cache as optional: a cache failure (Redis outage, timeout, or an entry that no longer
 * deserializes) is logged and the call proceeds as a miss, so reads fall through to the database.
 * A failed eviction can leave an entry stale until its time-to-live expires. Each failure increments
 * {@code swirl.cache.errors} tagged by cache and operation.
 */
public class FailOpenCacheErrorHandler implements CacheErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(FailOpenCacheErrorHandler.class);

    private final MeterRegistry registry;

    /** Records on the global registry, which Spring Boot links to the application's registries. */
    public FailOpenCacheErrorHandler() {
        this(Metrics.globalRegistry);
    }

    public FailOpenCacheErrorHandler(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        warn("get", cache, exception);
    }

    @Override
    public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
        warn("put", cache, exception);
    }

    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        warn("evict", cache, exception);
    }

    @Override
    public void handleCacheClearError(RuntimeException exception, Cache cache) {
        warn("clear", cache, exception);
    }

    private void warn(String operation, Cache cache, RuntimeException exception) {
        Counter.builder("swirl.cache.errors").description("Cache operations that failed and fell through")
                .tag("cache", cache.getName()).tag("operation", operation).register(registry).increment();
        log.warn("Cache {} failed for cache={}; continuing without the cache: {}",
                operation, cache.getName(), exception.getClass().getName());
    }
}
