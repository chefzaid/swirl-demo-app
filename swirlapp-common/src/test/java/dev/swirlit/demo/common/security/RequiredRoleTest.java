package dev.swirlit.demo.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;

class RequiredRoleTest {

    private final TestingAuthenticationToken admin = new TestingAuthenticationToken("a", null, "ROLE_user-admin");
    private final TestingAuthenticationToken auditor = new TestingAuthenticationToken("b", null, "ROLE_auditor");

    @Test
    void passesOnlyCallersWithTheRole() {
        RequiredRole role = new RequiredRole("user-admin");

        assertThat(role.isMetBy(admin)).isTrue();
        assertThat(role.isMetBy(auditor)).isFalse();
        assertThat(role.isMetBy(null)).isFalse();
    }

    @Test
    void passesEveryCallerWhenNoRoleIsConfigured() {
        assertThat(new RequiredRole("").isMetBy(auditor)).isTrue();
        assertThat(new RequiredRole(" ").isMetBy(auditor)).isTrue();
        assertThat(new RequiredRole(null).isMetBy(auditor)).isTrue();
    }
}
