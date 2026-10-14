package dev.swirlit.demo.order.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import dev.swirlit.demo.common.domain.OrderStatus;
import dev.swirlit.demo.common.event.OrderEvent;
import dev.swirlit.demo.order.domain.Order;
import dev.swirlit.demo.order.domain.ProcessedEvent;
import dev.swirlit.demo.order.repository.OrderRepository;
import dev.swirlit.demo.order.repository.ProcessedEventRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderResultListenerTest {

    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ProcessedEventRepository processedEventRepository;
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private OrderResultListener orderResultListener;

    @BeforeEach
    void setUp() {
        orderResultListener = new OrderResultListener(orderRepository, processedEventRepository, registry,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void consumeUpdatesExistingOrder() {
        Order existing = new Order(1L, 1001L);
        existing.setId(1L);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(existing));

        orderResultListener.consume(event(1L, OrderStatus.APPROVED, "Ada Lovelace"), null);

        assertEquals(OrderStatus.APPROVED, existing.getStatus());
        assertEquals("Ada Lovelace", existing.getUserName());
    }

    @Test
    void consumeAppliesRejectedResult() {
        Order existing = new Order(1L, 1001L);
        existing.setId(1L);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(existing));

        orderResultListener.consume(event(1L, OrderStatus.REJECTED, null), null);

        assertEquals(OrderStatus.REJECTED, existing.getStatus());
        assertEquals(null, existing.getUserName());
    }

    @Test
    void consumeIgnoresUnknownOrder() {
        when(orderRepository.findById(404L)).thenReturn(Optional.empty());

        orderResultListener.consume(event(404L, OrderStatus.REJECTED, null), null);

        verify(orderRepository).findById(404L);
    }

    @Test
    void consumeIgnoresSupersededOrderIdentity() {
        Order existing = new Order(2L, 1001L);
        existing.setId(1L);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(existing));

        orderResultListener.consume(event(1L, OrderStatus.APPROVED, "Ada Lovelace"), null);
        assertEquals(OrderStatus.PENDING, existing.getStatus());
    }

    @Test
    void consumeIsIdempotentForDuplicateResult() {
        Order existing = new Order(1L, 1001L);
        existing.setId(1L);
        existing.setStatus(OrderStatus.APPROVED);
        existing.setUserName("Ada Lovelace");
        when(orderRepository.findById(1L)).thenReturn(Optional.of(existing));

        orderResultListener.consume(event(1L, OrderStatus.APPROVED, "Ada Lovelace"), null);

        assertEquals(OrderStatus.APPROVED, existing.getStatus());
    }

    @Test
    void consumeRejectsMissingIdentifiers() {
        OrderEvent invalid = new OrderEvent(null, 1L, 1001L, null, OrderStatus.APPROVED, Instant.now());

        assertThrows(IllegalArgumentException.class, () -> orderResultListener.consume(invalid, null));
    }

    @Test
    void consumeRejectsNonFinalResultStatus() {
        assertThrows(IllegalArgumentException.class,
                () -> orderResultListener.consume(event(1L, OrderStatus.PENDING, null), null));
    }

    @Test
    void consumeRejectsDifferentResultForFinalOrder() {
        Order existing = new Order(1L, 1001L);
        existing.setId(1L);
        existing.setStatus(OrderStatus.APPROVED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(existing));

        assertThrows(IllegalArgumentException.class,
                () -> orderResultListener.consume(event(1L, OrderStatus.REJECTED, null), null));
    }

    @Test
    void recordsAResultInTheInboxWhenItIsApplied() {
        Order existing = new Order(1L, 1001L);
        existing.setId(1L);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(existing));

        orderResultListener.consume(event(1L, OrderStatus.APPROVED, "Ada Lovelace"), header("order-outbox-5:result"));

        assertEquals(OrderStatus.APPROVED, existing.getStatus());
        ArgumentCaptor<ProcessedEvent> processed = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventRepository).save(processed.capture());
        assertEquals("order-outbox-5:result", processed.getValue().getEventId());
        assertEquals(NOW, processed.getValue().getProcessedAt());
    }

    @Test
    void skipsAndCountsAResultTheInboxAlreadyHolds() {
        when(processedEventRepository.existsById("order-outbox-5:result")).thenReturn(true);

        orderResultListener.consume(event(1L, OrderStatus.APPROVED, "Ada Lovelace"), header("order-outbox-5:result"));

        verify(orderRepository, never()).findById(1L);
        verify(processedEventRepository, never()).save(any());
        assertEquals(1.0, registry.counter("swirl.events.duplicates", "consumer", "order-results").count());
    }

    private static byte[] header(String eventId) {
        return eventId.getBytes(StandardCharsets.UTF_8);
    }

    private static OrderEvent event(Long orderId, OrderStatus status, String userName) {
        return new OrderEvent(orderId, 1L, 1001L, userName, status, Instant.now());
    }
}
