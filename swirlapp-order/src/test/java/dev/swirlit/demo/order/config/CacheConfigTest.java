package dev.swirlit.demo.order.config;

import dev.swirlit.demo.common.domain.OrderStatus;
import dev.swirlit.demo.order.domain.Order;
import dev.swirlit.demo.common.cache.FailOpenCacheErrorHandler;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

class CacheConfigTest {

    @Test
    void environmentsUseDistinctCacheKeys() {
        var mapper = JsonMapper.builder().findAndAddModules().build();
        assertEquals("swirl-demo-app:int:orders:v1::", CacheConfig.cacheConfiguration(mapper, "swirl-demo-app:int:").getKeyPrefixFor("orders"));
        assertEquals("swirl-demo-app:prod:orders:v1::", CacheConfig.cacheConfiguration(mapper, "swirl-demo-app:prod:").getKeyPrefixFor("orders"));
    }

    @Test
    void cacheValuesRetainTheirOrderType() {
        Order order = new Order(17L, 2501L);
        order.setId(23L);

        var serialization = CacheConfig.cacheConfiguration(JsonMapper.builder().findAndAddModules().build(), "")
                .getValueSerializationPair();

        Order restored = assertInstanceOf(Order.class, serialization.read(serialization.write(order)));
        assertEquals(23L, restored.getId());
        assertEquals(OrderStatus.PENDING, restored.getStatus());
    }

    @Test
    void cacheFailuresFallThroughToTheDatabase() {
        assertInstanceOf(FailOpenCacheErrorHandler.class, new CacheConfig().errorHandler());
    }

    @Test
    void cacheIsKnownAtStartupSoItsStatisticsAreExported() {
        var manager = assertInstanceOf(RedisCacheManager.class, new CacheConfig().cacheManager(
                mock(RedisConnectionFactory.class), JsonMapper.builder().build(), ""));
        manager.initializeCaches();

        assertEquals(java.util.Set.of("orders"), java.util.Set.copyOf(manager.getCacheNames()));
    }
}
