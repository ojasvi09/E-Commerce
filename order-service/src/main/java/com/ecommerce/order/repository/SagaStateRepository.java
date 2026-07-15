package com.ecommerce.order.repository;

import com.ecommerce.order.entity.SagaState;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SagaStateRepository extends JpaRepository<SagaState, Long> {
}
