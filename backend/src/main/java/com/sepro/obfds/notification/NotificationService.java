package com.sepro.obfds.notification;

import com.sepro.obfds.dto.NotificationResponse;
import com.sepro.obfds.entity.Customer;
import com.sepro.obfds.entity.Notification;
import com.sepro.obfds.entity.NotificationType;
import com.sepro.obfds.exception.ResourceNotFoundException;
import com.sepro.obfds.repository.NotificationRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The alert and notification module as seen by the customer (FR-16).
 *
 * <p>In this simulation a notification is an in-application message rather than a real
 * SMS or email. The additional verification code is delivered the same way, which keeps the
 * demonstration self-contained and avoids any external messaging service.</p>
 */
@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @Transactional
    public Notification notify(
            Customer customer,
            NotificationType type,
            String title,
            String message,
            String relatedReference) {

        Notification notification = new Notification();
        notification.setCustomer(customer);
        notification.setType(type);
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setRelatedReference(relatedReference);
        notification.setRead(false);
        return notificationRepository.save(notification);
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> listForCustomer(Long customerId) {
        return notificationRepository.findByCustomerIdOrderByCreatedAtDesc(customerId).stream()
                .map(NotificationService::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public long countUnread(Long customerId) {
        return notificationRepository.countByCustomerIdAndReadFalse(customerId);
    }

    /**
     * Marks one notification as read.
     *
     * <p>The customer identifier is part of the lookup so a customer cannot mark somebody else
     * notification as read (NFR-08).</p>
     */
    @Transactional
    public NotificationResponse markAsRead(Long notificationId, Long customerId) {
        Notification notification = notificationRepository
                .findByIdAndCustomerId(notificationId, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification"));
        notification.setRead(true);
        return toResponse(notificationRepository.save(notification));
    }

    @Transactional
    public int markAllAsRead(Long customerId) {
        List<Notification> unread = notificationRepository.findByCustomerIdOrderByCreatedAtDesc(customerId)
                .stream()
                .filter(notification -> !notification.isRead())
                .toList();
        unread.forEach(notification -> notification.setRead(true));
        notificationRepository.saveAll(unread);
        return unread.size();
    }

    public static NotificationResponse toResponse(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType().name(),
                notification.getTitle(),
                notification.getMessage(),
                notification.getRelatedReference(),
                notification.isRead(),
                notification.getCreatedAt());
    }
}
