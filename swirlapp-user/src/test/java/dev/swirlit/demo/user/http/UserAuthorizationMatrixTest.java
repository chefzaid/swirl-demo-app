package dev.swirlit.demo.user.http;

import java.util.List;
import java.util.Map;

import dev.swirlit.demo.common.http.HttpApiTestSupport;
import dev.swirlit.demo.user.service.UserService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Who may call what, over real HTTP with Keycloak-shaped tokens: anonymous and forged callers are rejected,
 * any authenticated caller reads, and only the configured {@code user-admin} realm role deletes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {"app.security.enabled=true", "app.security.roles.user-admin=user-admin"})
@ExtendWith(OutputCaptureExtension.class)
class UserAuthorizationMatrixTest {

    @LocalServerPort
    private int port;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private UserService userService;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = HttpApiTestSupport.client(port);
        HttpApiTestSupport.acceptTokens(jwtDecoder, Map.of(
                "member", List.of(),
                "auditor", List.of("auditor"),
                "admin", List.of("auditor", "user-admin")));
    }

    @ParameterizedTest(name = "{0} {1} as {2} -> {3}")
    @CsvSource({
            "GET,    /api/users,   anonymous, 401",
            "GET,    /api/users,   forged,    401",
            "GET,    /api/users,   member,    200",
            "GET,    /api/users,   auditor,   200",
            "DELETE, /api/users/1, anonymous, 401",
            "DELETE, /api/users/1, forged,    401",
            "DELETE, /api/users/1, member,    403",
            "DELETE, /api/users/1, auditor,   403",
            "DELETE, /api/users/1, admin,     204",
    })
    void enforcesTheAccessPolicy(HttpMethod method, String path, String caller, int expected) {
        RestClient.RequestBodySpec request = client.method(method).uri(path);
        if (!"anonymous".equals(caller)) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + caller);
        }

        ResponseEntity<String> response = request.retrieve().toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(expected);
        if (expected == 403) {
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        }
    }

    @ParameterizedTest
    @CsvSource({"member", "auditor"})
    void deniedDeletesNeverReachTheServiceAndAreAudited(String caller, CapturedOutput output) {
        client.delete().uri("/api/users/1").header(HttpHeaders.AUTHORIZATION, "Bearer " + caller)
                .retrieve().toBodilessEntity();

        verify(userService, never()).deleteUser(anyLong());
        assertThat(output).contains("Audit authorization.denied endpoint - by " + HttpApiTestSupport.SUBJECT);
    }

    @Test
    void permittedDeletesReachTheService() {
        client.delete().uri("/api/users/1").header(HttpHeaders.AUTHORIZATION, "Bearer admin")
                .retrieve().toBodilessEntity();

        verify(userService).deleteUser(1L);
    }
}
