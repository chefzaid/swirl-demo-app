package dev.swirlit.demo.common.http;

import java.time.Instant;

import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.client.RestClient;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Shared setup for tests that call a service over a real HTTP connection. */
public final class HttpApiTestSupport {

    public static final String TOKEN = "integration-token";
    public static final String BEARER = "Bearer " + TOKEN;
    public static final String SUBJECT = "subject-42";

    private HttpApiTestSupport() {
    }

    /** A client for the server on {@code port} that returns every response instead of throwing on errors. */
    public static RestClient client(int port) {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    /** Makes a mocked decoder accept {@link #TOKEN} for {@link #SUBJECT} and reject every other token. */
    public static void acceptOnlyTestToken(JwtDecoder jwtDecoder) {
        when(jwtDecoder.decode(anyString())).thenAnswer(invocation -> {
            if (!TOKEN.equals(invocation.getArgument(0))) {
                throw new BadJwtException("unknown token");
            }
            return Jwt.withTokenValue(TOKEN).header("alg", "RS256").subject(SUBJECT)
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        });
    }
}
