package dev.swirlit.demo.common.event;

import java.time.Instant;

import dev.swirlit.demo.common.domain.OrderStatus;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderEventTest {

    private static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");

    @Test
    void newEventsAndResultsCarryTheCurrentVersion() {
        OrderEvent request = new OrderEvent(1L, 2L, 3L, null, OrderStatus.PENDING, NOW);

        assertThat(request.schemaVersion()).isEqualTo(OrderEvent.SCHEMA_VERSION);
        assertThat(request.withResult("Ada", OrderStatus.APPROVED).schemaVersion()).isEqualTo(OrderEvent.SCHEMA_VERSION);
    }

    @Test
    void eventsWrittenBeforeVersioningAreVersionOne() {
        assertThat(new OrderEvent(null, 1L, 2L, 3L, null, OrderStatus.PENDING, NOW).schemaVersion()).isEqualTo(1);
    }

    @Test
    void refusesOnlyNewerMajorVersions() {
        assertThatCode(new OrderEvent(1, 1L, 2L, 3L, null, OrderStatus.PENDING, NOW)::requireReadableSchema)
                .doesNotThrowAnyException();
        assertThatThrownBy(new OrderEvent(OrderEvent.SCHEMA_VERSION + 1, 1L, 2L, 3L, null, OrderStatus.PENDING, NOW)
                ::requireReadableSchema)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Order event schema version 2 is newer than supported version 1");
    }
}
