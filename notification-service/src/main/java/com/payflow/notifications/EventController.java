package com.payflow.notifications;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class EventController {

    private final EventConsumerService consumer;
    private final NotificationRepository notifications;
    private final String internalToken;

    public EventController(EventConsumerService consumer,
                           NotificationRepository notifications,
                           @Value("${notification.internal-token}") String internalToken) {
        this.consumer = consumer;
        this.notifications = notifications;
        this.internalToken = internalToken;
    }

    public record EventEnvelope(@NotNull Long eventId,
                                @NotBlank String eventType,
                                @NotBlank String payload) {}

    /**
     * Internal endpoint called by PayFlow's outbox relay.
     * Returns 200 for both first delivery AND duplicates — from the publisher's
     * point of view the event is "handled" either way, so it can mark it published.
     */
    @PostMapping("/internal/events")
    public ResponseEntity<?> receive(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                     @Valid @RequestBody EventEnvelope envelope) {
        if (!internalToken.equals(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid_internal_token"));
        }
        boolean processed = consumer.process(envelope.eventId(), envelope.eventType(), envelope.payload());
        return ResponseEntity.ok(Map.of("eventId", envelope.eventId(), "duplicate", !processed));
    }

    /** Demo endpoint: see the notifications a user has received. */
    @GetMapping("/api/notifications/{userId}")
    public List<Notification> forUser(@PathVariable Long userId) {
        return notifications.findByUserIdOrderByIdDesc(userId);
    }
}
