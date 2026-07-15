package com.ecommerce.order.repository;

import com.ecommerce.order.entity.OutboxEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findTop50ByPublishedAtIsNullOrderByIdAsc();
}
