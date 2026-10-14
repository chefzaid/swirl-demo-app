package dev.swirlit.demo.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/** Operational actions such as dead-letter replay, referenced from {@code @PreAuthorize} as {@code @operatorAccess}. */
@Component("operatorAccess")
public class OperatorAccess {

    private final RequiredRole operator;

    public OperatorAccess(@Value("${app.security.roles.operator:}") String operatorRole) {
        this.operator = new RequiredRole(operatorRole);
    }

    /** Needs the configured operator realm role; with none configured, any authenticated caller may. */
    public boolean allows(Authentication authentication) {
        return operator.isMetBy(authentication);
    }
}
