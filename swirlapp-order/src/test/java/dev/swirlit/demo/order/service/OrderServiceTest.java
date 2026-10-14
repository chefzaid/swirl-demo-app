package dev.swirlit.demo.order.service;

import java.util.List;
import java.util.Optional;

import dev.swirlit.demo.common.audit.AuditTrail;
import dev.swirlit.demo.common.domain.OrderStatus;
import dev.swirlit.demo.common.event.OrderEvent;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderEventOutbox outbox;
    @Mock
    private AuditTrail auditTrail;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, outbox, auditTrail);
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
    void getOrderRejectsUnknownId() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> orderService.getOrderById(99L));
    }

    @Test
    void createOrderSavesPendingOrderAndRecordsItsEvent() {
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order value = invocation.getArgument(0);
            value.setId(7L);
            return value;
        });

        Order result = orderService.createOrder(new CreateOrderRequest(2L, 2001L));

        assertEquals(OrderStatus.PENDING, result.getStatus());
        OrderEvent event = recordedEvent();
        assertEquals(7L, event.orderId());
        assertEquals(2L, event.userId());
        assertEquals(2001L, event.productId());
        assertEquals(OrderStatus.PENDING, event.status());
        verify(auditTrail).changed("order.created", "order", 7L);
    }

    @Test
    void updateOrderResetsValidationAndRecordsANewEvent() {
        Order existing = order(7L);
        existing.setStatus(OrderStatus.APPROVED);
        existing.setUserName("Ada Lovelace");
        when(orderRepository.findById(7L)).thenReturn(Optional.of(existing));

        Order updated = orderService.updateOrder(7L, new UpdateOrderRequest(2L, 2002L));

        assertEquals(2L, updated.getUserId());
        assertEquals(2002L, updated.getProductId());
        assertEquals(OrderStatus.PENDING, updated.getStatus());
        assertNull(updated.getUserName());
        OrderEvent event = recordedEvent();
        assertEquals(2L, event.userId());
        assertEquals(2002L, event.productId());
        verify(auditTrail).changed("order.updated", "order", 7L);
    }

    @Test
    void updateOrderLeavesUnchangedOrderAlone() {
        Order existing = order(7L);
        existing.setStatus(OrderStatus.APPROVED);
        when(orderRepository.findById(7L)).thenReturn(Optional.of(existing));

        Order updated = orderService.updateOrder(7L, new UpdateOrderRequest(1L, 1001L));

        assertEquals(OrderStatus.APPROVED, updated.getStatus());
        verify(outbox, never()).enqueue(any());
    }

    @Test
    void deleteOrderRemovesExistingOrder() {
        Order existing = order(7L);
        when(orderRepository.findById(7L)).thenReturn(Optional.of(existing));

        orderService.deleteOrder(7L);

        verify(orderRepository).delete(existing);
        verify(auditTrail).changed("order.deleted", "order", 7L);
    }

    private OrderEvent recordedEvent() {
        ArgumentCaptor<OrderEvent> event = ArgumentCaptor.forClass(OrderEvent.class);
        verify(outbox).enqueue(event.capture());
        return event.getValue();
    }

    private static Order order(Long id) {
        Order order = new Order(1L, 1001L);
        order.setId(id);
        return order;
    }
}
