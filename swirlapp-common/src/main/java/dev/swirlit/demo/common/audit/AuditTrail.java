package dev.swirlit.demo.common.audit;

import dev.swirlit.demo.common.config.AuditorAwareImpl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authorization.event.AuthorizationDeniedEvent;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Application audit trail: one structured log line per security-sensitive action, on the
 * {@code dev.swirlit.demo.audit} logger so the log platform can route, retain, and restrict it apart from
 * diagnostic logs. Entries name the actor by token subject and the resource by ID; they never carry
 * request bodies, user names, or email addresses.
 */
@Component
public class AuditTrail {

    private static final Logger audit = LoggerFactory.getLogger("dev.swirlit.demo.audit");
    private static final AuthenticationTrustResolver TRUST = new AuthenticationTrustResolverImpl();

    private final AuditorAwareImpl auditor = new AuditorAwareImpl();

    /** Records a successful change once its transaction commits, so rolled-back work leaves no entry. */
    public void changed(String action, String resourceType, Object resourceId) {
        String actor = currentActor();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    write(action, resourceType, resourceId, actor, "success");
                }
            });
            return;
        }
        write(action, resourceType, resourceId, actor, "success");
    }

    @EventListener
    public void authenticationFailed(AbstractAuthenticationFailureEvent event) {
        write("authentication.failed", "token", null, "anonymous", event.getException().getClass().getSimpleName());
    }

    @EventListener
    public void authorizationDenied(AuthorizationDeniedEvent<?> event) {
        Authentication authentication = event.getAuthentication().get();
        String actor = authentication == null || TRUST.isAnonymous(authentication) ? "anonymous" : authentication.getName();
        write("authorization.denied", "endpoint", null, actor, "denied");
    }

    private String currentActor() {
        return auditor.getCurrentAuditor().orElse("system");
    }

    private static void write(String action, String resourceType, Object resourceId, String actor, String outcome) {
        audit.atInfo()
                .addKeyValue("audit.action", action)
                .addKeyValue("audit.resource_type", resourceType)
                .addKeyValue("audit.resource_id", resourceId)
                .addKeyValue("audit.actor", actor)
                .addKeyValue("audit.outcome", outcome)
                .log("Audit {} {} {} by {}: {}", action, resourceType, resourceId == null ? "-" : resourceId,
                        actor, outcome);
    }
}
