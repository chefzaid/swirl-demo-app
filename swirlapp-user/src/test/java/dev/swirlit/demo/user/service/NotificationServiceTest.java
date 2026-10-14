package dev.swirlit.demo.user.service;

import java.time.Instant;

import dev.swirlit.demo.common.domain.OrderStatus;
import dev.swirlit.demo.common.event.OrderEvent;
import dev.swirlit.demo.user.domain.User;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class NotificationServiceTest {

    @Test
    void logsIdentifiersButNoPersonalData(CapturedOutput output) {
        User user = new User("Ada Lovelace", "ada.lovelace", "ada@example.test");
        user.setId(7L);

        new NotificationService().notifyUser(user, new OrderEvent(10L, 7L, 1001L, null, OrderStatus.PENDING, Instant.now()));

        assertThat(output).contains("Demo notification user=7 order=10 product=1001")
                .doesNotContain("Ada Lovelace", "ada.lovelace", "ada@example.test");
    }
}
