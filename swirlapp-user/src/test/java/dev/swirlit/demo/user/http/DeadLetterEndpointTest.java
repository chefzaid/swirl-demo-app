package dev.swirlit.demo.user.http;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import dev.swirlit.demo.common.http.HttpApiTestSupport;
import dev.swirlit.demo.common.messaging.deadletter.DeadLetter;
import dev.swirlit.demo.common.messaging.deadletter.DeadLetters;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code /actuator/deadletters} over real HTTP: who may list and replay, and how bad requests are answered. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {"app.security.enabled=true", "app.messaging.enabled=true",
        "app.security.roles.operator=operator"})
class DeadLetterEndpointTest {

    private static final DeadLetter LETTER = new DeadLetter("order_topic.DLT", 0, 7L, Instant.EPOCH, "order_topic",
            "42", "order-outbox-9", "java.lang.IllegalArgumentException", "unknown user", null, 120);

    @LocalServerPort
    private int port;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private DeadLetters deadLetters;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = HttpApiTestSupport.client(port);
        HttpApiTestSupport.acceptTokens(jwtDecoder, Map.of("member", List.of(), "operator", List.of("operator")));
    }

    @Test
    void onlyOperatorsListDeadLetters() {
        when(deadLetters.newest(20)).thenReturn(List.of(LETTER));

        assertThat(list(null).getStatusCode().value()).isEqualTo(401);
        assertThat(list("member").getStatusCode().value()).isEqualTo(403);
        ResponseEntity<String> allowed = list("operator");
        assertThat(allowed.getStatusCode().value()).isEqualTo(200);
        assertThat(allowed.getBody()).contains("\"eventId\":\"order-outbox-9\"", "\"reason\":\"unknown user\"");
    }

    @Test
    void onlyOperatorsReplayAndBadRequestsAreRejected() {
        when(deadLetters.replay("order_topic.DLT", 0, 7L)).thenReturn(LETTER);
        when(deadLetters.replay("order_topic", 0, 7L))
                .thenThrow(new IllegalArgumentException("Not a dead-letter topic of this service: order_topic"));

        assertThat(replay("member", "order_topic.DLT").getStatusCode().value()).isEqualTo(403);
        verify(deadLetters, never()).replay(anyString(), anyInt(), anyLong());

        assertThat(replay("operator", "order_topic.DLT").getStatusCode().value()).isEqualTo(200);
        assertThat(replay("operator", "order_topic").getStatusCode().value()).isEqualTo(400);
    }

    private ResponseEntity<String> list(String caller) {
        var request = client.get().uri("/actuator/deadletters");
        if (caller != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + caller);
        }
        return request.retrieve().toEntity(String.class);
    }

    private ResponseEntity<String> replay(String caller, String topic) {
        return client.post().uri("/actuator/deadletters").header(HttpHeaders.AUTHORIZATION, "Bearer " + caller)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("topic", topic, "partition", 0, "offset", 7))
                .retrieve().toEntity(String.class);
    }
}
