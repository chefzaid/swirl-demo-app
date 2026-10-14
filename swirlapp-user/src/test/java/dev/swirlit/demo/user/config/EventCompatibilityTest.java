package dev.swirlit.demo.user.config;

import dev.swirlit.demo.common.event.EventCompatibilityContract;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class EventCompatibilityTest extends EventCompatibilityContract {
}
