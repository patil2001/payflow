package com.payflow.outbox;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Transactional Outbox pattern.
 *
 * Problem: "save to DB AND publish to Kafka" is a dual write — if the app
 * crashes between the two, systems diverge.
 * Fix: write the event to this table IN THE SAME LOCAL TRANSACTION as the
 * business change. A relay polls unpublished rows and pushes them to the
 * broker. At-least-once delivery -> consumers must be idempotent.
 */
@Entity
@Table(name = "outbox_events", indexes = @Index(name = "idx_outbox_unpublished", columnList = "published, createdAt"))
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String aggregateType;   // e.g. "Transfer"

    @Column(nullable = false)
    private String aggregateId;     // e.g. transferId

    @Column(nullable = false)
    private String eventType;       // e.g. "TRANSFER_COMPLETED"

    @Column(nullable = false, length = 4000)
    private String payloadJson;

    @Column(nullable = false)
    private boolean published = false;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private Instant publishedAt;

    protected OutboxEvent() {}

    public OutboxEvent(String aggregateType, String aggregateId, String eventType, String payloadJson) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payloadJson = payloadJson;
    }

    public void markPublished() {
        this.published = true;
        this.publishedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getPayloadJson() { return payloadJson; }
    public boolean isPublished() { return published; }
}
