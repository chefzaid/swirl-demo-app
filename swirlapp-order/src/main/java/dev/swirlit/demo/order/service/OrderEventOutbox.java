package dev.swirlit.demo.order.service;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

import dev.swirlit.demo.common.event.OrderEvent;
import dev.swirlit.demo.common.util.Constants;
import dev.swirlit.demo.order.domain.OutboxEvent;
import dev.swirlit.demo.order.repository.OutboxEventRepository;

import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records order events in the {@code order_outbox} table inside the caller's transaction, so an event exists
 * exactly when its order change commits. {@link OutboxRelay} publishes the rows to Kafka afterwards, continuing
 * the trace stored with each row.
 */
@Component
public class OrderEventOutbox {

    static final String TRACEPARENT = "traceparent";

    private final OutboxEventRepository outboxEventRepository;
    private final JsonMapper jsonMapper;
    private final boolean messagingEnabled;
    private final String orderTopic;
    private final Clock clock;
    private final Tracer tracer;
    private final Propagator propagator;

    @Autowired
    public OrderEventOutbox(
            OutboxEventRepository outboxEventRepository,
            JsonMapper jsonMapper,
            ObjectProvider<Tracer> tracer,
            ObjectProvider<Propagator> propagator,
            @Value("${app.messaging.enabled:false}") boolean messagingEnabled,
            @Value("${app.messaging.topics.order:" + Constants.ORDER_TOPIC + "}") String orderTopic) {
        this(outboxEventRepository, jsonMapper, messagingEnabled, orderTopic, Clock.systemUTC(),
                tracer.getIfAvailable(() -> Tracer.NOOP), propagator.getIfAvailable(() -> Propagator.NOOP));
    }

    OrderEventOutbox(OutboxEventRepository outboxEventRepository, JsonMapper jsonMapper, boolean messagingEnabled,
            String orderTopic, Clock clock, Tracer tracer, Propagator propagator) {
        this.outboxEventRepository = outboxEventRepository;
        this.jsonMapper = jsonMapper;
        this.messagingEnabled = messagingEnabled;
        this.orderTopic = orderTopic;
        this.clock = clock;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** Joins the caller's transaction; without messaging no relay runs, so nothing is recorded. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(OrderEvent event) {
        if (!messagingEnabled) {
            return;
        }
        outboxEventRepository.save(new OutboxEvent(orderTopic, event.orderId().toString(),
                jsonMapper.writeValueAsString(event), clock.instant(), currentTraceParent()));
    }

    private String currentTraceParent() {
        TraceContext context = tracer.currentTraceContext().context();
        if (context == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(context, carrier, Map::put);
        return carrier.get(TRACEPARENT);
    }
}
