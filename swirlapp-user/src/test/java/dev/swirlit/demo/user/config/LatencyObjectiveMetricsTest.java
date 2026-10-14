package dev.swirlit.demo.user.config;

import java.util.Arrays;

import dev.swirlit.demo.user.service.UserService;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LatencyObjectiveMetricsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private CacheManager cacheManager;

    @Test
    void requestTimersPublishTheLatencyObjectiveBuckets() throws Exception {
        mockMvc.perform(get("/api/users"));

        var snapshot = meterRegistry.get("http.server.requests").tag("uri", "/api/users").timer().takeSnapshot();
        assertThat(Arrays.stream(snapshot.histogramCounts()).map(CountAtBucket::bucket))
                .containsExactly(300_000_000.0, 1_000_000_000.0);
    }
}
