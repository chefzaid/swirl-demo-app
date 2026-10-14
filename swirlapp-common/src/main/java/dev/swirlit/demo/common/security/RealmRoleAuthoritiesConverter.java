package dev.swirlit.demo.common.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Grants a token's scopes as {@code SCOPE_*} authorities, as Spring Security does by default, and its Keycloak
 * realm roles ({@code realm_access.roles}) as {@code ROLE_*} authorities, so {@code hasRole('user-admin')} checks
 * a role assigned in Keycloak. Tokens without the claim keep their scope authorities only.
 */
public final class RealmRoleAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    static final String REALM_ACCESS = "realm_access";

    private final JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>(scopes.convert(jwt));
        if (jwt.getClaim(REALM_ACCESS) instanceof Map<?, ?> realmAccess
                && realmAccess.get("roles") instanceof Collection<?> roles) {
            roles.stream()
                    .filter(String.class::isInstance)
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .forEach(authorities::add);
        }
        return authorities;
    }
}
