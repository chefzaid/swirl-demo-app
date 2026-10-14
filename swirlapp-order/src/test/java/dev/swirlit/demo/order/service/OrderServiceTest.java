package dev.swirlit.demo.order.service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import dev.swirlit.demo.common.domain.OrderStatus;
import dev.swirlit.demo.common.event.OrderEvent;
import dev.swirlit.demo.common.util.Constants;
import dev.swirlit.demo.order.domain.Order;
import dev.swirlit.demo.order.dto.CreateOrderRequest;
import dev.swirlit.demo.order.dto.UpdateOrderRequest;
import dev.swirlit.demo.order.repository.OrderRepository;
import jakarta.persistence.EntityNotFoundException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import dev.swirlit.demo.common.audit.AuditTrail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private KafkaTemplate<Object, Object> kafkaTemplate;
    @Mock
    private AuditTrail auditTrail;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, kafkaTemplate, auditTrail, true, Constants.ORDER_TOPIC);
    }

    @Test
    void publishesOnlyToSelectedEnvironment() {
        orderService = new OrderService(orderRepository, kafkaTemplate, auditTrail, true, "swirl-demo-app.uat.order_topic");
        Order order = new Order(1L, 2L);
        order.setId(7L);
        when(orderRepository.save(any(Order.class))).thenReturn(order);
        when(kafkaTemplate.send(eq("swirl-demo-app.uat.order_topic"), eq("7"), any(OrderEvent.class)))
                .thenReturn(new java.util.concurrent.CompletableFuture<>());
        orderService.createOrder(new CreateOrderRequest(1L, 2L));
        verify(kafkaTemplate).send(eq("swirl-demo-app.uat.order_topic"), eq("7"), any(OrderEvent.class));
        verify(auditTrail).changed("order.created", "order", 7L);
    }

    @Test
    void getAllOrdersSortsNewestFirst() {
        Order order = order(1L);
        when(orderRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(order)));

        assertEquals(List.of(order), orderService.getAllOrders(25));
    }

    @Test
    void getOrderReturnsOrder() {
        Order order = order(1L);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertEquals(order, orderService.getOrderById(1L));
    }

    @Test
    void createOrderSavesPendingOrderAndPublishesEvent() {
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order value = invocation.getArgument(0);
            value.setId(7L);
            return value;
        });
        when(kafkaTemplate.send(eq(Constants.ORDER_TOPIC), eq("7"), any(OrderEvent.class)))
                .thenReturn(new CompletableFuture<>());

        Order result = orderService.createOrder(new CreateOrderRequest(2L, 2001L));

        assertEquals(OrderStatus.PENDING, result.getStatus());
        ArgumentCaptor<OrderEvent> event = ArgumentCaptor.forClass(OrderEvent.class);
        verify(kafkaTemplate).send(eq(Constants.ORDER_TOPIC), eq("7"), event.capture());
        assertEquals(2L, event.getValue().userId());
    }

    @Test
    void createOrderSkipsKafkaWhenMessagingIsDisabled() {
        orderService = new OrderService(orderRepository, kafkaTemplate, auditTrail, false, Constants.ORDER_TOPIC);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        orderService.createOrder(new CreateOrderRequest(1L, 1001L));

        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    @Test
    void createOrderPublishesOnlyAfterTransactionCommit() {
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order value = invocation.getArgument(0);
            value.setId(7L);
            return value;
        });
        when(kafkaTemplate.send(eq(Constants.ORDER_TOPIC), eq("7"), any(OrderEvent.class)))
                .thenReturn(new CompletableFuture<>());

        TransactionSynchronizationManager.initSynchronization();
        try {
            orderService.createOrder(new CreateOrderRequest(2L, 2001L));
            verify(kafkaTemplate, never()).send(any(), any(), any());

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(synchronization -> synchronization.afterCommit());

            verify(kafkaTemplate).send(eq(Constants.ORDER_TOPIC), eq("7"), any(OrderEvent.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void createOrderDoesNotReportFailureAfterCommitWhenPublisherInitializationFails() {
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order value = invocation.getArgument(0);
            value.setId(7L);
            return value;
        });
        when(kafkaTemplate.send(eq(Constants.ORDER_TOPIC), eq("7"), any(OrderEvent.class)))
                .thenThrow(new IllegalStateException("producer configuration failed"));

        assertDoesNotThrow(() -> orderService.createOrder(new CreateOrderRequest(2L, 2001L)));
    }

    @Test
    void getOrderRejectsUnknownId() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> orderService.getOrderById(99L));
    }

    @Test
    void updateOrderResetsValidationAndPublishesNewEvent() {
        Order existing = order(7L);
        existing.setStatus(OrderStatus.APPROVED);
        existing.setUserName("Ada Lovelace");
        when(orderRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(kafkaTemplate.send(eq(Constants.ORDER_TOPIC), eq("7"), any(OrderEvent.class)))
                .thenReturn(new CompletableFuture<>());

        Order updated = orderService.updateOrder(7L, new UpdateOrderRequest(2L, 2002L));

        assertEquals(2L, updated.getUserId());
        assertEquals(2002L, updated.getProductId());
        assertEquals(OrderStatus.PENDING, updated.getStatus());
        assertEquals(null, updated.getUserName());
        ArgumentCaptor<OrderEvent> event = ArgumentCaptor.forClass(OrderEvent.class);
        verify(kafkaTemplate).send(eq(Constants.ORDER_TOPIC), eq("7"), event.capture());
        assertEquals(2L, event.getValue().userId());
        assertEquals(2002L, event.getValue().productId());
    }

    @Test
    void updateOrderLeavesUnchangedOrderAlone() {
        Order existing = order(7L);
        existing.setStatus(OrderStatus.APPROVED);
        when(orderRepository.findById(7L)).thenReturn(Optional.of(existing));

        Order updated = orderService.updateOrder(7L, new UpdateOrderRequest(1L, 1001L));

        assertEquals(OrderStatus.APPROVED, updated.getStatus());
        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    @Test
    void deleteOrderRemovesExistingOrder() {
        Order existing = order(7L);
        when(orderRepository.findById(7L)).thenReturn(Optional.of(existing));

        orderService.deleteOrder(7L);

        verify(orderRepository).delete(existing);
        verify(auditTrail).changed("order.deleted", "order", 7L);
    }

    private static Order order(Long id) {
        Order order = new Order(1L, 1001L);
        order.setId(id);
        return order;
    }
}
