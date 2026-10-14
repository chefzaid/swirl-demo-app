package dev.swirlit.demo.user.config;

import dev.swirlit.demo.user.service.UserService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.security.enabled=true")
@ExtendWith(OutputCaptureExtension.class)
class SecurityAuditTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private CacheManager cacheManager;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void auditsAnonymousRequestsThatAreDenied(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/api/users")).andExpect(status().isUnauthorized());

        assertThat(output).contains("Audit authorization.denied endpoint - by anonymous: denied");
    }

    @Test
    void auditsRejectedBearerTokensWithoutTheirContent(CapturedOutput output) throws Exception {
        when(jwtDecoder.decode(anyString())).thenThrow(new BadJwtException("signature mismatch"));

        mockMvc.perform(get("/api/users").header("Authorization", "Bearer forged.token.value"))
                .andExpect(status().isUnauthorized());

        assertThat(output).contains("Audit authentication.failed token - by anonymous: InvalidBearerTokenException")
                .doesNotContain("forged.token.value");
    }
}
