package com.payflow.notifications;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventConsumerService {

    private static final Logger log = LoggerFactory.getLogger(EventConsumerService.class);

    private final ProcessedEventRepository processedEvents;
    private final NotificationRepository notifications;
    private final ObjectMapper objectMapper;

    public EventConsumerService(ProcessedEventRepository processedEvents,
                                NotificationRepository notifications,
                                ObjectMapper objectMapper) {
        this.processedEvents = processedEvents;
        this.notifications = notifications;
        this.objectMapper = objectMapper;
    }

    /**
     * @return true if processed now, false if it was a duplicate (already processed).
     *
     * Dedup check + notification insert + processed-event insert happen in ONE
     * local transaction: either the event is fully processed or not at all.
     * The unique constraint on eventId is the authoritative guard — the
     * existsByEventId check is just a fast path to skip work.
     */
    @Transactional
    public boolean process(Long eventId, String eventType, String payloadJson) {
        if (processedEvents.existsByEventId(eventId)) {
            log.info("Duplicate event {} ignored (idempotent consumer)", eventId);
            return false;
        }

        try {
            processedEvents.save(new ProcessedEvent(eventId));
        } catch (DataIntegrityViolationException e) {
            // Concurrent duplicate delivery lost the race — same outcome: skip.
            log.info("Duplicate event {} ignored (constraint)", eventId);
            return false;
        }

        if ("TRANSFER_COMPLETED".equals(eventType)) {
            handleTransferCompleted(payloadJson);
        } else {
            log.warn("Unknown event type '{}' for event {} — acknowledged and skipped", eventType, eventId);
        }
        return true;
    }

    private void handleTransferCompleted(String payloadJson) {
        try {
            JsonNode payload = objectMapper.readTree(payloadJson);
            long fromUserId = payload.get("fromUserId").asLong();
            long toUserId = payload.get("toUserId").asLong();
            long amountMinor = payload.get("amountMinor").asLong();
            String amount = "₹" + (amountMinor / 100) + "." + String.format("%02d", amountMinor % 100);

            // In production: hand off to an email/SMS/push provider with its own retry queue.
            notifications.save(new Notification(fromUserId, Notification.Channel.PUSH,
                    "You sent " + amount, Notification.Status.SENT));
            notifications.save(new Notification(toUserId, Notification.Channel.PUSH,
                    "You received " + amount, Notification.Status.SENT));

            log.info("Notifications sent for transfer {}: {} -> {}",
                    payload.get("transferId").asText(), fromUserId, toUserId);
        } catch (Exception e) {
            // Malformed payload would poison the retry loop forever -> in production
            // this goes to a dead-letter table for manual inspection.
            throw new IllegalArgumentException("Bad TRANSFER_COMPLETED payload: " + payloadJson, e);
        }
    }
}
