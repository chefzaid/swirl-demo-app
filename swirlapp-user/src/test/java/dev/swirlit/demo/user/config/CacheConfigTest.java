package dev.swirlit.demo.user.config;

import dev.swirlit.demo.user.domain.User;
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
        assertEquals("swirl-demo-app:int:users:v1::", CacheConfig.cacheConfiguration(mapper, "swirl-demo-app:int:").getKeyPrefixFor("users"));
        assertEquals("swirl-demo-app:prod:users:v1::", CacheConfig.cacheConfiguration(mapper, "swirl-demo-app:prod:").getKeyPrefixFor("users"));
    }

    @Test
    void cacheValuesRetainTheirUserType() {
        User user = new User("Grace Hopper", "grace", "grace@example.test");
        user.setId(17L);

        var serialization = CacheConfig.cacheConfiguration(JsonMapper.builder().findAndAddModules().build(), "")
                .getValueSerializationPair();

        User restored = assertInstanceOf(User.class, serialization.read(serialization.write(user)));
        assertEquals(17L, restored.getId());
        assertEquals("grace", restored.getUsername());
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

        assertEquals(java.util.Set.of("users"), java.util.Set.copyOf(manager.getCacheNames()));
    }
}
