package dev.swirlit.demo.order.service;

import java.time.Instant;
import java.util.List;

import dev.swirlit.demo.common.domain.OrderStatus;
import dev.swirlit.demo.common.event.OrderEvent;
import dev.swirlit.demo.common.util.Constants;
import dev.swirlit.demo.order.domain.Order;
import dev.swirlit.demo.order.dto.CreateOrderRequest;
import dev.swirlit.demo.order.dto.UpdateOrderRequest;
import dev.swirlit.demo.order.repository.OrderRepository;
import jakarta.persistence.EntityNotFoundException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final boolean messagingEnabled;
    private final String orderTopic;

    public OrderService(
            OrderRepository orderRepository,
            KafkaTemplate<Object, Object> kafkaTemplate,
            @Value("${app.messaging.enabled:false}") boolean messagingEnabled,
            @Value("${app.messaging.topics.order:" + Constants.ORDER_TOPIC + "}") String orderTopic) {
        this.orderRepository = orderRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.messagingEnabled = messagingEnabled;
        this.orderTopic = orderTopic;
    }

    @Transactional(readOnly = true)
    public List<Order> getAllOrders(int limit) {
        return orderRepository.findAll(PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "id"))).getContent();
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "orders", key = "#id")
    public Order getOrderById(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Order %d was not found".formatted(id)));
    }

    @Transactional
    @CacheEvict(cacheNames = "orders", allEntries = true)
    public Order createOrder(CreateOrderRequest request) {
        Order saved = orderRepository.save(new Order(request.userId(), request.productId()));
        publishPendingOrder(saved);
        return saved;
    }

    @Transactional
    @CacheEvict(cacheNames = "orders", allEntries = true)
    public Order updateOrder(Long orderId, UpdateOrderRequest request) {
        Order order = findOrder(orderId);
        if (order.getUserId().equals(request.userId()) && order.getProductId().equals(request.productId())) {
            return order;
        }

        order.setUserId(request.userId());
        order.setProductId(request.productId());
        order.setUserName(null);
        order.setStatus(OrderStatus.PENDING);
        publishPendingOrder(order);
        return order;
    }

    @Transactional
    @CacheEvict(cacheNames = "orders", allEntries = true)
    public void deleteOrder(Long orderId) {
        orderRepository.delete(findOrder(orderId));
    }

    private Order findOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order %d was not found".formatted(orderId)));
    }

    private void publishPendingOrder(Order order) {
        if (!messagingEnabled) {
            return;
        }

        OrderEvent event = new OrderEvent(
                order.getId(), order.getUserId(), order.getProductId(), null, order.getStatus(), Instant.now());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendPendingOrder(event);
                }
            });
            return;
        }

        sendPendingOrder(event);
    }

    private void sendPendingOrder(OrderEvent event) {
        try {
            kafkaTemplate.send(orderTopic, event.orderId().toString(), event)
                    .whenComplete((result, error) -> {
                        if (error == null) {
                            log.info("Published order event id={} partition={}",
                                    event.orderId(), result.getRecordMetadata().partition());
                        } else {
                            log.error("Could not publish order event id={}", event.orderId(), error);
                        }
                    });
        } catch (RuntimeException error) {
            log.error("Could not initialize order event publication id={}", event.orderId(), error);
        }
    }
}
