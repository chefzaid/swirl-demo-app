package dev.swirlit.demo.common.security;

import org.springframework.security.core.Authentication;

/**
 * A realm role an operation needs. A blank name means the deployment has not configured one, and any
 * authenticated caller passes, so an environment whose identity provider has no such role keeps working.
 */
public record RequiredRole(String name) {

    public boolean isMetBy(Authentication authentication) {
        if (name == null || name.isBlank()) {
            return true;
        }
        String authority = "ROLE_" + name;
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(granted -> authority.equals(granted.getAuthority()));
    }
}
