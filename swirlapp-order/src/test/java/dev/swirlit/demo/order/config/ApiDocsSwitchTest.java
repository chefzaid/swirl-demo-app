package dev.swirlit.demo.order.config;

import dev.swirlit.demo.order.service.OrderService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"app.security.enabled=true", "APP_API_DOCS_ENABLED=false"})
class ApiDocsSwitchTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private CacheManager cacheManager;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void requiresAuthenticationForApiDocsWhenTheEnvironmentDisablesThem() throws Exception {
        mockMvc.perform(get("/api/orders/openapi"))
                .andExpect(status().isUnauthorized());
    }
}
