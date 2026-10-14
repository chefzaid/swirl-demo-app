package dev.swirlit.demo.common.http;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
        acceptTokens(jwtDecoder, Map.of(TOKEN, List.of()));
    }

    /**
     * Makes a mocked decoder accept each token in {@code realmRolesByToken} for {@link #SUBJECT}, carrying the
     * listed Keycloak realm roles, and reject every other token.
     */
    public static void acceptTokens(JwtDecoder jwtDecoder, Map<String, List<String>> realmRolesByToken) {
        when(jwtDecoder.decode(anyString())).thenAnswer(invocation -> {
            String token = invocation.getArgument(0);
            List<String> roles = realmRolesByToken.get(token);
            if (roles == null) {
                throw new BadJwtException("unknown token");
            }
            return Jwt.withTokenValue(token).header("alg", "RS256").subject(SUBJECT)
                    .claim("realm_access", Map.of("roles", roles))
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        });
    }
}
