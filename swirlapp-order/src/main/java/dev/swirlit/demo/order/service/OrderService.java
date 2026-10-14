package dev.swirlit.demo.order.service;

import java.time.Instant;
import java.util.List;

import dev.swirlit.demo.common.audit.AuditTrail;
import dev.swirlit.demo.common.domain.OrderStatus;
import dev.swirlit.demo.common.event.OrderEvent;
import dev.swirlit.demo.order.domain.Order;
import dev.swirlit.demo.order.dto.CreateOrderRequest;
import dev.swirlit.demo.order.dto.UpdateOrderRequest;
import dev.swirlit.demo.order.repository.OrderRepository;
import jakarta.persistence.EntityNotFoundException;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private static final String RESOURCE = "order";

    private final OrderRepository orderRepository;
    private final OrderEventOutbox outbox;
    private final AuditTrail auditTrail;

    public OrderService(OrderRepository orderRepository, OrderEventOutbox outbox, AuditTrail auditTrail) {
        this.orderRepository = orderRepository;
        this.outbox = outbox;
        this.auditTrail = auditTrail;
    }

    @Transactional(readOnly = true)
    public List<Order> getAllOrders(int limit) {
        return orderRepository.findAll(PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "id"))).getContent();
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "orders", key = "#id", sync = true)
    public Order getOrderById(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Order %d was not found".formatted(id)));
    }

    @Transactional
    @CacheEvict(cacheNames = "orders", allEntries = true)
    public Order createOrder(CreateOrderRequest request) {
        Order saved = orderRepository.save(new Order(request.userId(), request.productId()));
        auditTrail.changed("order.created", RESOURCE, saved.getId());
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
        auditTrail.changed("order.updated", RESOURCE, orderId);
        publishPendingOrder(order);
        return order;
    }

    @Transactional
    @CacheEvict(cacheNames = "orders", allEntries = true)
    public void deleteOrder(Long orderId) {
        orderRepository.delete(findOrder(orderId));
        auditTrail.changed("order.deleted", RESOURCE, orderId);
    }

    private Order findOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order %d was not found".formatted(orderId)));
    }

    private void publishPendingOrder(Order order) {
        outbox.enqueue(new OrderEvent(
                order.getId(), order.getUserId(), order.getProductId(), null, order.getStatus(), Instant.now()));
    }
}
