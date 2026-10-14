package dev.swirlit.demo.common.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/** Maps Keycloak realm roles to authorities and enforces {@code @PreAuthorize} while API security is on. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("app.security.enabled")
@EnableMethodSecurity
public class JwtAuthorizationConfig {

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new RealmRoleAuthoritiesConverter());
        return converter;
    }
}
