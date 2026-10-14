package dev.swirlit.demo.order.service;

import java.time.Clock;

import dev.swirlit.demo.common.event.OrderEvent;
import dev.swirlit.demo.common.util.Constants;
import dev.swirlit.demo.order.domain.OutboxEvent;
import dev.swirlit.demo.order.repository.OutboxEventRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records order events in the {@code order_outbox} table inside the caller's transaction, so an event exists
 * exactly when its order change commits. {@link OutboxRelay} publishes the rows to Kafka afterwards.
 */
@Component
public class OrderEventOutbox {

    private final OutboxEventRepository outboxEventRepository;
    private final JsonMapper jsonMapper;
    private final boolean messagingEnabled;
    private final String orderTopic;
    private final Clock clock;

    @Autowired
    public OrderEventOutbox(
            OutboxEventRepository outboxEventRepository,
            JsonMapper jsonMapper,
            @Value("${app.messaging.enabled:false}") boolean messagingEnabled,
            @Value("${app.messaging.topics.order:" + Constants.ORDER_TOPIC + "}") String orderTopic) {
        this(outboxEventRepository, jsonMapper, messagingEnabled, orderTopic, Clock.systemUTC());
    }

    OrderEventOutbox(OutboxEventRepository outboxEventRepository, JsonMapper jsonMapper, boolean messagingEnabled,
            String orderTopic, Clock clock) {
        this.outboxEventRepository = outboxEventRepository;
        this.jsonMapper = jsonMapper;
        this.messagingEnabled = messagingEnabled;
        this.orderTopic = orderTopic;
        this.clock = clock;
    }

    /** Joins the caller's transaction; without messaging no relay runs, so nothing is recorded. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(OrderEvent event) {
        if (!messagingEnabled) {
            return;
        }
        outboxEventRepository.save(new OutboxEvent(
                orderTopic, event.orderId().toString(), jsonMapper.writeValueAsString(event), clock.instant()));
    }
}
