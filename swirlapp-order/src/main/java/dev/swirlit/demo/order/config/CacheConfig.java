package dev.swirlit.demo.order.config;

import dev.swirlit.demo.common.cache.FailOpenCacheErrorHandler;
import dev.swirlit.demo.order.domain.Order;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Set;

@Configuration
@EnableCaching
@Profile({"uat", "prod"})
public class CacheConfig implements CachingConfigurer {

    /** Bump when the cached class changes shape so new pods never read entries written by old ones. */
    static final String CACHE_VERSION = "v1";

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory redisConnectionFactory, JsonMapper jsonMapper,
            @Value("${REDIS_CACHE_PREFIX:}") String keyPrefix) {
        return RedisCacheManager.builder(redisConnectionFactory)
                .cacheDefaults(cacheConfiguration(jsonMapper, keyPrefix))
                .initialCacheNames(Set.of("orders"))
                .enableStatistics()
                .build();
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new FailOpenCacheErrorHandler();
    }

    static RedisCacheConfiguration cacheConfiguration(JsonMapper jsonMapper, String keyPrefix) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .computePrefixWith(cacheName -> keyPrefix + cacheName + ":" + CACHE_VERSION + "::")
                .entryTtl(Duration.ofMinutes(10))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new JacksonJsonRedisSerializer<>(jsonMapper, Order.class)));
    }
}
