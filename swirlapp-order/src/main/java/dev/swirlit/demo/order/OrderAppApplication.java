package dev.swirlit.demo.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableCaching
@EnableScheduling
@SpringBootApplication(scanBasePackages = {"dev.swirlit.demo.order", "dev.swirlit.demo.common"})
@EntityScan("dev.swirlit.demo.order.domain")
public class OrderAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderAppApplication.class, args);
    }
}
