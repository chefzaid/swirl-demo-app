package dev.swirlit.demo.user.config;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import dev.swirlit.demo.user.service.UserService;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.mock.MockProducerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TracingTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Tracer tracer;

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private CacheManager cacheManager;

    @Test
    void continuesTheCallersTraceAndExposesItToLogs() throws Exception {
        AtomicReference<String> traceId = new AtomicReference<>();
        when(userService.getAllUsers(100)).thenAnswer(invocation -> {
            traceId.set(MDC.get("traceId"));
            return List.of();
        });

        mockMvc.perform(get("/api/users")
                .header("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"));

        assertThat(traceId).hasValue("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    void publishedEventsCarryTheTraceToTheirConsumers() {
        var producer = new MockProducer<>(true, null, new StringSerializer(), new StringSerializer());
        var template = new KafkaTemplate<>(new MockProducerFactory<>(() -> producer));
        var admin = new KafkaAdmin(Map.of());
        admin.setClusterId("test-cluster");
        template.setKafkaAdmin(admin);
        template.setObservationEnabled(true);
        template.setApplicationContext(context);
        template.afterSingletonsInstantiated();

        Span span = tracer.nextSpan().name("create order").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            template.send("order_topic", "7", "event");
        } finally {
            span.end();
        }

        String traceparent = new String(producer.history().getFirst().headers().lastHeader("traceparent").value());
        assertThat(traceparent).contains(span.context().traceId());
    }
}
