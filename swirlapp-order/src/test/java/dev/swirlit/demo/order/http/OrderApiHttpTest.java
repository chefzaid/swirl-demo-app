package dev.swirlit.demo.order.http;

import java.util.Map;

import dev.swirlit.demo.common.http.HttpApiTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static dev.swirlit.demo.common.http.HttpApiTestSupport.BEARER;
import static dev.swirlit.demo.common.http.HttpApiTestSupport.SUBJECT;
import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the order API over a real HTTP connection with the full filter chain and an in-memory database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {"app.security.enabled=true", "app.rate-limit.enabled=true"})
@ExtendWith(OutputCaptureExtension.class)
class OrderApiHttpTest {

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
    void anonymousCallersAreChallengedForABearerToken() {
        ResponseEntity<String> response = client.get().uri("/api/orders").retrieve().toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
    }

    @Test
    void anOrderCanBeCreatedReadChangedAndDeleted(CapturedOutput output) {
        ResponseEntity<Map> created = client.post().uri("/api/orders").header(HttpHeaders.AUTHORIZATION, BEARER)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("userId", 1, "productId", 2501))
                .retrieve().toEntity(Map.class);

        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getHeaders().getFirst("X-Request-Id")).isNotBlank();
        assertThat(created.getHeaders().getFirst("RateLimit-Limit")).isNotBlank();
        assertThat(created.getBody()).containsEntry("status", "PENDING");
        String location = created.getHeaders().getLocation().toString();
        Object id = created.getBody().get("id");

        ResponseEntity<Map> read = client.get().uri(location).header(HttpHeaders.AUTHORIZATION, BEARER)
                .retrieve().toEntity(Map.class);
        assertThat(read.getStatusCode().value()).isEqualTo(200);
        assertThat(read.getBody()).containsEntry("id", id).containsEntry("productId", 2501);

        ResponseEntity<Map> changed = client.put().uri(location).header(HttpHeaders.AUTHORIZATION, BEARER)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("userId", 1, "productId", 2502))
                .retrieve().toEntity(Map.class);
        assertThat(changed.getBody()).containsEntry("productId", 2502).containsEntry("status", "PENDING");

        assertThat(client.delete().uri(location).header(HttpHeaders.AUTHORIZATION, BEARER)
                .retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(204);
        ResponseEntity<Map> gone = client.get().uri(location).header(HttpHeaders.AUTHORIZATION, BEARER)
                .retrieve().toEntity(Map.class);
        assertThat(gone.getStatusCode().value()).isEqualTo(404);
        assertThat(gone.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);

        assertThat(output).contains("Audit order.created order " + id + " by " + SUBJECT + ": success",
                "Audit order.deleted order " + id + " by " + SUBJECT + ": success");
    }

    @Test
    void invalidInputIsRejectedAsProblemDetails() {
        ResponseEntity<Map> response = client.post().uri("/api/orders").header(HttpHeaders.AUTHORIZATION, BEARER)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("userId", 0, "productId", 2501))
                .retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).containsEntry("title", "Validation failed");
    }

    @Test
    void rejectedTokensNeverReachTheApi() {
        ResponseEntity<String> response = client.get().uri("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer forged").retrieve().toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).contains("invalid_token");
    }

    @Test
    void clientsCanAskForLinksAndAffordancesInsteadOfPlainJson() throws Exception {
        String location = client.post().uri("/api/orders").header(HttpHeaders.AUTHORIZATION, BEARER)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("userId", 2, "productId", 2501))
                .retrieve().toBodilessEntity().getHeaders().getLocation().toString();

        ResponseEntity<String> plain = client.get().uri(location).header(HttpHeaders.AUTHORIZATION, BEARER)
                .accept(MediaType.APPLICATION_JSON).retrieve().toEntity(String.class);
        assertThat(plain.getBody()).doesNotContain("_links");

        ResponseEntity<String> hypermedia = client.get().uri(location).header(HttpHeaders.AUTHORIZATION, BEARER)
                .accept(MediaTypes.HAL_FORMS_JSON).retrieve().toEntity(String.class);
        assertThat(hypermedia.getHeaders().getContentType()).isEqualTo(MediaTypes.HAL_FORMS_JSON);
        JsonNode body = JsonMapper.shared().readTree(hypermedia.getBody());
        assertThat(body.path("status").asString()).isEqualTo("PENDING");
        assertThat(body.path("_links").path("self").path("href").asString()).endsWith(location);
        assertThat(body.path("_links").path("user").path("href").asString()).isEqualTo("/api/users/2");
        JsonNode update = body.path("_templates").path("updateOrder");
        assertThat(update.path("method").asString()).isEqualTo("PUT");
        assertThat(update.path("properties").findValuesAsString("name")).containsExactlyInAnyOrder("userId", "productId");
        assertThat(update.path("properties").findValues("readOnly")).allSatisfy(readOnly -> assertThat(readOnly.asBoolean()).isFalse());
        assertThat(update.path("properties").findValues("required")).allSatisfy(required -> assertThat(required.asBoolean()).isTrue());
        assertThat(body.path("_templates").path("deleteOrder").path("method").asString()).isEqualTo("DELETE");
    }
}
