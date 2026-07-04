package com.payflow.notifications;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Idempotent-consumer ledger: one row per event we have already handled.
 *
 * PayFlow's outbox relay guarantees AT-LEAST-ONCE delivery — the same event
 * WILL arrive twice (relay crash between publish and mark-published, network
 * retries). The unique constraint on eventId is what makes reprocessing safe:
 * the second INSERT fails, we return 200, and no duplicate notification is sent.
 */
@Entity
@Table(name = "processed_events", uniqueConstraints =
        @UniqueConstraint(name = "uq_event_id", columnNames = "eventId"))
public class ProcessedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long eventId;

    @Column(nullable = false)
    private Instant processedAt = Instant.now();

    protected ProcessedEvent() {}

    public ProcessedEvent(Long eventId) {
        this.eventId = eventId;
    }
}
