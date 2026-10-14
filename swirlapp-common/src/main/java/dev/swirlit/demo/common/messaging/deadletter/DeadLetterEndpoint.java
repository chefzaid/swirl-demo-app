package dev.swirlit.demo.common.messaging.deadletter;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.actuate.endpoint.InvalidEndpointRequestException;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

/**
 * {@code /actuator/deadletters}: lists the newest dead-lettered records and replays one. The endpoint is not
 * routed by the ingress, needs a bearer token while API security is on, and needs the operator role when
 * {@code app.security.roles.operator} names one.
 */
@Component
@Endpoint(id = "deadletters")
@ConditionalOnProperty(name = "app.messaging.enabled", havingValue = "true")
public class DeadLetterEndpoint {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 200;

    private final DeadLetters deadLetters;

    public DeadLetterEndpoint(DeadLetters deadLetters) {
        this.deadLetters = deadLetters;
    }

    @ReadOperation
    @PreAuthorize("@operatorAccess.allows(authentication)")
    public List<DeadLetter> newest(@Nullable Integer limit) {
        int bounded = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        return deadLetters.newest(bounded);
    }

    @WriteOperation
    @PreAuthorize("@operatorAccess.allows(authentication)")
    public DeadLetter replay(String topic, int partition, long offset) {
        try {
            return deadLetters.replay(topic, partition, offset);
        } catch (IllegalArgumentException exception) {
            throw new InvalidEndpointRequestException(exception.getMessage(), exception.getMessage());
        }
    }
}
