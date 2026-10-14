package dev.swirlit.demo.common.audit;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.event.AuthorizationDeniedEvent;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class AuditTrailTest {

    private final AuditTrail auditTrail = new AuditTrail();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void recordsTheTokenSubjectAndResourceIdWithoutPersonalData(CapturedOutput output) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("3f2c6a1e-subject", "token", "ROLE_USER"));

        auditTrail.changed("user.updated", "user", 42L);

        assertThat(output).contains("Audit user.updated user 42 by 3f2c6a1e-subject: success");
    }

    @Test
    void writesTransactionalChangesOnlyAfterCommit(CapturedOutput output) {
        TransactionSynchronizationManager.initSynchronization();

        auditTrail.changed("order.created", "order", 7L);
        assertThat(output).doesNotContain("order.created");

        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        assertThat(output).contains("Audit order.created order 7 by system: success");
    }

    @Test
    void recordsRejectedTokensAndDeniedRequests(CapturedOutput output) {
        auditTrail.authenticationFailed(new AuthenticationFailureBadCredentialsEvent(
                new TestingAuthenticationToken("forged", "token"), new BadCredentialsException("bad signature")));
        auditTrail.authorizationDenied(new AuthorizationDeniedEvent<>(
                () -> new AnonymousAuthenticationToken("key", "anonymousUser",
                        AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")),
                "/api/users", new AuthorizationDecision(false)));
        auditTrail.authorizationDenied(new AuthorizationDeniedEvent<>(
                () -> new TestingAuthenticationToken("subject-9", "token", "ROLE_USER"),
                "/api/users", new AuthorizationDecision(false)));

        assertThat(output).contains("Audit authentication.failed token - by anonymous: BadCredentialsException",
                "Audit authorization.denied endpoint - by anonymous: denied",
                "Audit authorization.denied endpoint - by subject-9: denied")
                .doesNotContain("forged", "bad signature");
    }
}
