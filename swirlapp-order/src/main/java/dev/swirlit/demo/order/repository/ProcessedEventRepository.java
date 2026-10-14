package dev.swirlit.demo.order.repository;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import dev.swirlit.demo.order.domain.ProcessedEvent;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {

    @Modifying
    @Query("delete from ProcessedEvent e where e.processedAt < :cutoff")
    int deleteProcessedBefore(Instant cutoff);
}
