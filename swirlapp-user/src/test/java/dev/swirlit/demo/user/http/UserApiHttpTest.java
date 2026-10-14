package dev.swirlit.demo.user.http;

import java.util.List;
import java.util.Map;

import dev.swirlit.demo.common.http.HttpApiTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

import static dev.swirlit.demo.common.http.HttpApiTestSupport.BEARER;
import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the user API over a real HTTP connection with the full filter chain and an in-memory database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {"app.security.enabled=true", "app.cors.allowed-origins=https://app.example.test"})
class UserApiHttpTest {

    @LocalServerPort
    private int port;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = HttpApiTestSupport.client(port);
        HttpApiTestSupport.acceptOnlyTestToken(jwtDecoder);
    }

    @Test
    void aUserCanBeCreatedFoundAndDeletedButNotDuplicated() {
        Map<String, String> grace = Map.of("name", "Grace Hopper", "username", "grace", "email", "grace@example.test");
        ResponseEntity<Map> created = client.post().uri("/api/users").header(HttpHeaders.AUTHORIZATION, BEARER)
                .contentType(MediaType.APPLICATION_JSON).body(grace).retrieve().toEntity(Map.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        String location = created.getHeaders().getLocation().toString();

        ResponseEntity<Map> duplicate = client.post().uri("/api/users").header(HttpHeaders.AUTHORIZATION, BEARER)
                .contentType(MediaType.APPLICATION_JSON).body(grace).retrieve().toEntity(Map.class);
        assertThat(duplicate.getStatusCode().value()).isEqualTo(409);
        assertThat(duplicate.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);

        List<Map<String, Object>> all = client.get().uri("/api/users").header(HttpHeaders.AUTHORIZATION, BEARER)
                .retrieve().body(new ParameterizedTypeReference<>() { });
        assertThat(all).anySatisfy(user -> assertThat(user).containsEntry("username", "grace"));

        assertThat(client.delete().uri(location).header(HttpHeaders.AUTHORIZATION, BEARER)
                .retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(204);
        assertThat(client.get().uri(location).header(HttpHeaders.AUTHORIZATION, BEARER)
                .retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void browsersFromTheConfiguredOriginPassPreflightWithoutCredentials() {
        ResponseEntity<Void> allowed = client.options().uri("/api/users/1")
                .header(HttpHeaders.ORIGIN, "https://app.example.test")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type")
                .retrieve().toBodilessEntity();
        assertThat(allowed.getStatusCode().value()).isEqualTo(200);
        assertThat(allowed.getHeaders().getAccessControlAllowOrigin()).isEqualTo("https://app.example.test");

        ResponseEntity<Void> foreign = client.options().uri("/api/users/1")
                .header(HttpHeaders.ORIGIN, "https://evil.example.test")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "DELETE")
                .retrieve().toBodilessEntity();
        assertThat(foreign.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void probesAnswerWithoutATokenWhileTheApiDoesNot() {
        assertThat(client.get().uri("/actuator/health/readiness").retrieve().toBodilessEntity()
                .getStatusCode().value()).isEqualTo(200);
        assertThat(client.get().uri("/api/users").retrieve().toBodilessEntity()
                .getStatusCode().value()).isEqualTo(401);
    }
}
