package dev.swirlit.demo.common.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

class RealmRoleAuthoritiesConverterTest {

    private final RealmRoleAuthoritiesConverter converter = new RealmRoleAuthoritiesConverter();

    @Test
    void grantsScopesAndRealmRoles() {
        Jwt jwt = token(Map.of("scope", "profile email",
                RealmRoleAuthoritiesConverter.REALM_ACCESS, Map.of("roles", List.of("user-admin", "offline_access"))));

        assertThat(authorities(jwt)).containsExactly(
                "SCOPE_profile", "SCOPE_email", "ROLE_user-admin", "ROLE_offline_access");
    }

    @Test
    void keepsScopesWhenTheTokenHasNoRealmRoles() {
        assertThat(authorities(token(Map.of("scope", "profile")))).containsExactly("SCOPE_profile");
        assertThat(authorities(token(Map.of(RealmRoleAuthoritiesConverter.REALM_ACCESS, Map.of())))).isEmpty();
    }

    @Test
    void ignoresMalformedRoleClaims() {
        assertThat(authorities(token(Map.of(RealmRoleAuthoritiesConverter.REALM_ACCESS, "user-admin")))).isEmpty();
        assertThat(authorities(token(Map.of(RealmRoleAuthoritiesConverter.REALM_ACCESS,
                Map.of("roles", List.of(7, "auditor")))))).containsExactly("ROLE_auditor");
    }

    private List<String> authorities(Jwt jwt) {
        return converter.convert(jwt).stream().map(GrantedAuthority::getAuthority).toList();
    }

    private static Jwt token(Map<String, Object> claims) {
        return Jwt.withTokenValue("token").header("alg", "RS256").subject("subject")
                .claims(all -> all.putAll(claims))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }
}
