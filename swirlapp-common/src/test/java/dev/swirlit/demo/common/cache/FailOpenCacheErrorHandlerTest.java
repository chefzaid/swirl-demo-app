package dev.swirlit.demo.common.cache;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class FailOpenCacheErrorHandlerTest {

    @Test
    void readsAndWritesFallThroughWhenTheCacheIsDown(CapturedOutput output) {
        try (var context = new AnnotationConfigApplicationContext(CachingTestConfig.class)) {
            Lookup lookup = context.getBean(Lookup.class);

            assertThat(lookup.find(1L)).isEqualTo("record-1");
            assertThat(lookup.find(1L)).isEqualTo("record-1");
            lookup.change();

            assertThat(lookup.loadCount()).isEqualTo(2);
            assertThat(output).contains("Cache get failed for cache=records; continuing without the cache",
                    "Cache clear failed for cache=records");
        }
    }

    @Test
    void logsTheFailedOperationWithoutKeysOrValues(CapturedOutput output) {
        var handler = new FailOpenCacheErrorHandler();
        var cache = new ConcurrentMapCache("people");
        var failure = new IllegalStateException("ada@example.test");

        handler.handleCachePutError(failure, cache, "ada", "Ada Lovelace");
        handler.handleCacheEvictError(failure, cache, "ada");

        assertThat(output).contains("Cache put failed for cache=people", "Cache evict failed for cache=people")
                .doesNotContain("ada@example.test", "Ada Lovelace");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    static class CachingTestConfig implements CachingConfigurer {

        @Override
        public CacheManager cacheManager() {
            SimpleCacheManager manager = new SimpleCacheManager();
            manager.setCaches(List.of(new UnavailableCache("records")));
            manager.initializeCaches();
            return manager;
        }

        @Override
        public CacheErrorHandler errorHandler() {
            return new FailOpenCacheErrorHandler();
        }

        @Bean
        Lookup lookup() {
            return new Lookup();
        }
    }

    static class Lookup {
        private final AtomicInteger loads = new AtomicInteger();

        public int loadCount() {
            return loads.get();
        }

        @Cacheable(cacheNames = "records", key = "#id", sync = true)
        public String find(Long id) {
            loads.incrementAndGet();
            return "record-" + id;
        }

        @CacheEvict(cacheNames = "records", allEntries = true)
        public void change() {
            // Writes evict every cached record.
        }
    }

    /** Behaves like a cache whose backing store is unreachable. */
    static class UnavailableCache extends ConcurrentMapCache {
        UnavailableCache(String name) {
            super(name);
        }

        @Override
        public ValueWrapper get(Object key) {
            throw new IllegalStateException("store unavailable");
        }

        @Override
        public <T> T get(Object key, Callable<T> valueLoader) {
            throw new IllegalStateException("store unavailable");
        }

        @Override
        public void put(Object key, Object value) {
            throw new IllegalStateException("store unavailable");
        }

        @Override
        public void clear() {
            throw new IllegalStateException("store unavailable");
        }

        @Override
        public Cache.ValueWrapper putIfAbsent(Object key, Object value) {
            throw new IllegalStateException("store unavailable");
        }
    }
}
