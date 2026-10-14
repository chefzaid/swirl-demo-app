package dev.swirlit.demo.order.config;

import dev.swirlit.demo.order.service.OrderService;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import jakarta.servlet.http.Cookie;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.security.enabled=true")
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private CacheManager cacheManager;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private KafkaProperties kafkaProperties;

    @Test
    void rejectsAnonymousApiRequest() throws Exception {
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void servesApiDocsAnonymouslyWhenEnabled() throws Exception {
        mockMvc.perform(get("/api/orders/openapi"))
                .andExpect(status().isOk());
    }

    @Test
    void allowsAuthenticatedApiRequest() throws Exception {
        when(orderService.getAllOrders(100)).thenReturn(List.of());

        mockMvc.perform(get("/api/orders").with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void allowsOrderUpdateAndDeleteCorsPreflight() throws Exception {
        mockMvc.perform(options("/api/orders/1")
                        .header("Origin", "http://localhost:4200")
                        .header("Access-Control-Request-Method", "DELETE"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS"));
    }

    @Test
    void rejectsWriteUsingCookiesOrAnExistingSession() throws Exception {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken("visitor", "unused", List.of()));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

        mockMvc.perform(delete("/api/orders/1")
                        .session(session)
                        .cookie(new Cookie("access_token", "cookie-is-not-bearer-auth")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void allowsBearerAuthenticatedWriteWithoutCsrfToken() throws Exception {
        mockMvc.perform(delete("/api/orders/1").with(jwt()))
                .andExpect(status().isNoContent())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void hasValidKafkaProducerConfiguration() {
        assertDoesNotThrow(() -> new ProducerConfig(kafkaProperties.buildProducerProperties()));
    }
}
