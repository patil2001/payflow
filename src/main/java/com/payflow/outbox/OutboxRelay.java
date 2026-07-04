package com.payflow.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Polls unpublished outbox rows and "publishes" them.
 * Here it logs; in production the publish() body is a KafkaTemplate.send()
 * (or Debezium tails the table via CDC and no relay code is needed at all).
 *
 * Delivery guarantee: at-least-once. If we crash after publishing but before
 * markPublished(), the event is sent again — which is why downstream consumers
 * must be idempotent (dedupe on aggregateId/eventId).
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository outbox;

    public OutboxRelay(OutboxEventRepository outbox) {
        this.outbox = outbox;
    }

    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void relay() {
        List<OutboxEvent> batch = outbox.findTop100ByPublishedFalseOrderByCreatedAtAsc();
        for (OutboxEvent event : batch) {
            publish(event);
            event.markPublished();
        }
        if (!batch.isEmpty()) {
            log.info("Outbox relay published {} event(s)", batch.size());
        }
    }

    private void publish(OutboxEvent event) {
        // KafkaTemplate.send("payflow.transfers", event.getAggregateId(), event.getPayloadJson())
        log.info("PUBLISH [{}] {} -> {}", event.getEventType(), event.getAggregateId(), event.getPayloadJson());
    }
}
