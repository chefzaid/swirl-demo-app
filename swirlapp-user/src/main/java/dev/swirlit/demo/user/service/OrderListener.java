package dev.swirlit.demo.user.service;

import dev.swirlit.demo.common.domain.OrderStatus;
import dev.swirlit.demo.common.event.EventIds;
import dev.swirlit.demo.common.event.OrderEvent;
import dev.swirlit.demo.common.util.Constants;
import dev.swirlit.demo.user.domain.User;
import jakarta.persistence.EntityNotFoundException;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

@Service
public class OrderListener {

    private static final Logger log = LoggerFactory.getLogger(OrderListener.class);

    private final UserService userService;
    private final NotificationService notificationService;
    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final String resultTopic;

    public OrderListener(
            UserService userService,
            NotificationService notificationService,
            KafkaTemplate<Object, Object> kafkaTemplate,
            @Value("${app.messaging.topics.result:" + Constants.ORDER_RESULT_TOPIC + "}") String resultTopic) {
        this.userService = userService;
        this.notificationService = notificationService;
        this.kafkaTemplate = kafkaTemplate;
        this.resultTopic = resultTopic;
    }

    @KafkaListener(topics = "${app.messaging.topics.order:" + Constants.ORDER_TOPIC + "}", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(OrderEvent event, @Header(name = EventIds.HEADER, required = false) byte[] eventId) {
        OrderEvent result;
        try {
            User user = userService.getUser(event.userId());
            notificationService.notifyUser(user, event);
            result = event.withResult(user.getName(), OrderStatus.APPROVED);
        } catch (EntityNotFoundException exception) {
            log.warn("Rejecting order {} because user {} does not exist", event.orderId(), event.userId());
            result = event.withResult(null, OrderStatus.REJECTED);
        }
        ProducerRecord<Object, Object> record = new ProducerRecord<>(resultTopic, event.orderId().toString(), result);
        String requestId = EventIds.read(eventId);
        EventIds.write(record.headers(), requestId == null ? null : EventIds.resultOf(requestId));
        kafkaTemplate.send(record).join();
    }
}
