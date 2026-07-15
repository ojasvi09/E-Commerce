package com.ecommerce.inventory.repository;

import com.ecommerce.inventory.entity.OutboxEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findTop50ByPublishedAtIsNullOrderByIdAsc();
}
