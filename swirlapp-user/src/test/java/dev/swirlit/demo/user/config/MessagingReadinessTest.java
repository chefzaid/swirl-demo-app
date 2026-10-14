package dev.swirlit.demo.user.config;

import dev.swirlit.demo.common.messaging.MessagingHealthIndicator;
import dev.swirlit.demo.user.service.UserService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MessagingReadinessTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MessagingHealthIndicator messaging;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private CacheManager cacheManager;

    @Test
    void optionalMessagingOutageKeepsThePodReady() throws Exception {
        when(messaging.health(anyBoolean())).thenReturn(Health.status(MessagingHealthIndicator.DEGRADED).build());

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEGRADED"));
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void requiredMessagingOutageTakesThePodOutOfService() throws Exception {
        when(messaging.health(anyBoolean())).thenReturn(Health.down().build());

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isServiceUnavailable());
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());
    }
}
