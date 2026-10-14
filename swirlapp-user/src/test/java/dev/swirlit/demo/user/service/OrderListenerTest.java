package dev.swirlit.demo.user.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import dev.swirlit.demo.common.domain.OrderStatus;
import dev.swirlit.demo.common.event.EventIds;
import dev.swirlit.demo.common.event.OrderEvent;
import dev.swirlit.demo.common.util.Constants;
import dev.swirlit.demo.user.domain.User;
import jakarta.persistence.EntityNotFoundException;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderListenerTest {

    @Mock
    private UserService userService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private KafkaTemplate<Object, Object> kafkaTemplate;
    private OrderListener orderListener;

    @BeforeEach
    void setUp() {
        orderListener = listener(Constants.ORDER_RESULT_TOPIC);
    }

    @Test
    void consumeApprovesKnownUser() {
        User user = new User("Ada Lovelace", "ada", "ada@example.test");
        when(userService.getUser(1L)).thenReturn(user);
        OrderEvent input = event(10L, 1L);
        successfulPublish();

        orderListener.consume(input, null);

        verify(notificationService).notifyUser(user, input);
        assertPublishedStatus(10L, OrderStatus.APPROVED, "Ada Lovelace");
    }

    @Test
    void consumeRejectsMissingUser() {
        when(userService.getUser(2L)).thenThrow(new EntityNotFoundException("missing"));
        successfulPublish();

        orderListener.consume(event(11L, 2L), null);

        assertPublishedStatus(11L, OrderStatus.REJECTED, null);
    }

    @Test
    void consumeRefusesANewerSchemaVersionWithoutAnswering() {
        OrderEvent future = new OrderEvent(OrderEvent.SCHEMA_VERSION + 1, 13L, 1L, 1001L, null, OrderStatus.PENDING,
                Instant.now());

        assertThrows(IllegalArgumentException.class, () -> orderListener.consume(future, null));

        verifyNoInteractions(userService);
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    @Test
    void consumeRetriesProcessingFailureInsteadOfRejectingOrder() {
        when(userService.getUser(3L)).thenThrow(new IllegalStateException("boom"));

        assertThrows(IllegalStateException.class, () -> orderListener.consume(event(12L, 3L), null));

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    @Test
    void publishesResultOnlyToSelectedEnvironment() {
        orderListener = listener("swirl-demo-app.int.order_result_topic");
        when(userService.getUser(1L)).thenReturn(new User("Ada", "ada", "ada@example.test"));
        successfulPublish();
        orderListener.consume(event(10L, 1L), null);
        assertEquals("swirl-demo-app.int.order_result_topic", publishedRecord().topic());
    }

    @Test
    void resultsCarryAnIdDerivedFromTheRequestSoRepeatsAreRecognised() {
        when(userService.getUser(1L)).thenReturn(new User("Ada", "ada", "ada@example.test"));
        successfulPublish();

        orderListener.consume(event(10L, 1L), "order-outbox-5".getBytes(StandardCharsets.UTF_8));

        Header header = publishedRecord().headers().lastHeader(EventIds.HEADER);
        assertEquals("order-outbox-5:result", new String(header.value(), StandardCharsets.UTF_8));
    }

    @Test
    void resultsOfRequestsWithoutAnIdCarryNone() {
        when(userService.getUser(1L)).thenReturn(new User("Ada", "ada", "ada@example.test"));
        successfulPublish();

        orderListener.consume(event(10L, 1L), null);

        assertNull(publishedRecord().headers().lastHeader(EventIds.HEADER));
    }

    private void assertPublishedStatus(Long orderId, OrderStatus status, String userName) {
        ProducerRecord<Object, Object> record = publishedRecord();
        assertEquals(Constants.ORDER_RESULT_TOPIC, record.topic());
        assertEquals(orderId.toString(), record.key());
        OrderEvent result = (OrderEvent) record.value();
        assertEquals(status, result.status());
        assertEquals(userName, result.userName());
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<Object, Object> publishedRecord() {
        ArgumentCaptor<ProducerRecord<Object, Object>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        return captor.getValue();
    }

    private static OrderEvent event(Long orderId, Long userId) {
        return new OrderEvent(orderId, userId, 1001L, null, OrderStatus.PENDING, Instant.now());
    }

    private OrderListener listener(String resultTopic) {
        return new OrderListener(userService, notificationService, kafkaTemplate, resultTopic);
    }

    private void successfulPublish() {
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
    }
}
