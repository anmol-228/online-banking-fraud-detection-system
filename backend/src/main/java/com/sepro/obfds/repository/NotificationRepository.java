package com.sepro.obfds.repository;

import com.sepro.obfds.entity.Notification;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    Optional<Notification> findByIdAndCustomerId(Long id, Long customerId);

    long countByCustomerIdAndReadFalse(Long customerId);
}
