package dev.swirlit.demo.user.config;

import dev.swirlit.demo.user.service.UserService;

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
    private UserService userService;

    @MockitoBean
    private CacheManager cacheManager;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void requiresAuthenticationForApiDocsWhenTheEnvironmentDisablesThem() throws Exception {
        mockMvc.perform(get("/api/users/openapi"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/docs"))
                .andExpect(status().isUnauthorized());
    }
}
