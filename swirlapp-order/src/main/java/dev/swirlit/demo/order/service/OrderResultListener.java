package dev.swirlit.demo.order.service;

import java.time.Clock;

import dev.swirlit.demo.common.domain.OrderStatus;
import dev.swirlit.demo.common.event.EventIds;
import dev.swirlit.demo.common.event.OrderEvent;
import dev.swirlit.demo.order.domain.ProcessedEvent;
import dev.swirlit.demo.order.repository.OrderRepository;
import dev.swirlit.demo.order.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderResultListener {

    private static final Logger log = LoggerFactory.getLogger(OrderResultListener.class);
    private final OrderRepository orderRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final Counter duplicates;
    private final Clock clock;

    @Autowired
    public OrderResultListener(OrderRepository orderRepository, ProcessedEventRepository processedEventRepository,
            MeterRegistry meterRegistry) {
        this(orderRepository, processedEventRepository, meterRegistry, Clock.systemUTC());
    }

    OrderResultListener(OrderRepository orderRepository, ProcessedEventRepository processedEventRepository,
            MeterRegistry meterRegistry, Clock clock) {
        this.orderRepository = orderRepository;
        this.processedEventRepository = processedEventRepository;
        this.clock = clock;
        this.duplicates = Counter.builder("swirl.events.duplicates")
                .description("Redelivered or republished events the inbox recognised and skipped")
                .tag("consumer", "order-results").register(meterRegistry);
    }

    /**
     * Applies a result once. Results carrying an event ID are recorded in the inbox in the same transaction, so
     * a redelivery after a crash or a republished result is skipped even across restarts; results without an ID
     * (from older producers) fall back to the state checks below.
     */
    @Transactional
    @CacheEvict(cacheNames = "orders", key = "#event.orderId()")
    @KafkaListener(topics = "${app.messaging.topics.result:order_result_topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(OrderEvent event, @Header(name = EventIds.HEADER, required = false) byte[] eventIdHeader) {
        validateResult(event);
        String eventId = EventIds.read(eventIdHeader);
        if (eventId != null) {
            if (processedEventRepository.existsById(eventId)) {
                duplicates.increment();
                log.debug("Skipping already processed result {} for order {}", eventId, event.orderId());
                return;
            }
            processedEventRepository.save(new ProcessedEvent(eventId, clock.instant()));
        }
        apply(event);
    }

    private void apply(OrderEvent event) {
        var order = orderRepository.findById(event.orderId()).orElse(null);
        if (order == null) {
            log.debug("Ignoring result for deleted order {}", event.orderId());
            return;
        }
        if (!order.getUserId().equals(event.userId()) || !order.getProductId().equals(event.productId())) {
            log.debug("Ignoring superseded result for edited order {}", event.orderId());
            return;
        }
        if (order.getStatus() == event.status()) {
            log.debug("Ignoring duplicate {} result for order {}", event.status(), event.orderId());
            return;
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new IllegalArgumentException("Order %d is already %s".formatted(event.orderId(), order.getStatus()));
        }
        order.setStatus(event.status());
        order.setUserName(event.userName());
    }

    private static void validateResult(OrderEvent event) {
        if (event == null || event.orderId() == null || event.userId() == null || event.productId() == null) {
            throw new IllegalArgumentException("Order result identifiers are required");
        }
        event.requireReadableSchema();
        if (event.status() != OrderStatus.APPROVED && event.status() != OrderStatus.REJECTED) {
            throw new IllegalArgumentException("Order result must be APPROVED or REJECTED");
        }
    }
}
