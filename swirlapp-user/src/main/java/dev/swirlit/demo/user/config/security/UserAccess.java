package dev.swirlit.demo.user.config.security;

import dev.swirlit.demo.common.security.RequiredRole;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/** Method-level rules for user operations, referenced from {@code @PreAuthorize} as {@code @userAccess}. */
@Component("userAccess")
public class UserAccess {

    private final RequiredRole userAdmin;

    public UserAccess(@Value("${app.security.roles.user-admin:}") String userAdminRole) {
        this.userAdmin = new RequiredRole(userAdminRole);
    }

    /** Deleting a user needs the configured user-admin realm role; with none configured, any caller may. */
    public boolean canDeleteUsers(Authentication authentication) {
        return userAdmin.isMetBy(authentication);
    }
}
