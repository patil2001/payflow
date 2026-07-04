package com.payflow.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Polls unpublished outbox rows and delivers them to the notification-service
 * over HTTP (in a Kafka deployment this publish() is a KafkaTemplate.send(),
 * or Debezium tails the table via CDC and no relay code exists at all).
 *
 * Failure semantics (the interview answer):
 *  - Publish fails (service down, timeout) -> event is NOT marked published,
 *    the next poll retries it. Nothing is lost.
 *  - Crash after publish but before markPublished -> event is sent AGAIN.
 *    => delivery is AT-LEAST-ONCE, and the consumer dedupes on eventId
 *       (see notification-service ProcessedEvent).
 *  - Per-event try/catch: one bad event doesn't block the batch, and ordering
 *    per aggregate is preserved by the createdAt sort.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository outbox;
    private final RestClient restClient;
    private final String internalToken;
    private final boolean webhookEnabled;

    public OutboxRelay(OutboxEventRepository outbox,
                       RestClient.Builder restClientBuilder,
                       @Value("${payflow.events.webhook-url:http://localhost:8081/internal/events}") String webhookUrl,
                       @Value("${payflow.events.internal-token:payflow-internal-dev-token}") String internalToken,
                       @Value("${payflow.events.webhook-enabled:true}") boolean webhookEnabled) {
        this.outbox = outbox;
        this.restClient = restClientBuilder.baseUrl(webhookUrl).build();
        this.internalToken = internalToken;
        this.webhookEnabled = webhookEnabled;
    }

    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void relay() {
        List<OutboxEvent> batch = outbox.findTop100ByPublishedFalseOrderByCreatedAtAsc();
        int published = 0;
        for (OutboxEvent event : batch) {
            try {
                publish(event);
                event.markPublished();
                published++;
            } catch (Exception e) {
                // Leave unpublished; retried on the next poll. Log and move on so
                // one unreachable consumer doesn't wedge the whole batch.
                log.warn("Failed to publish outbox event {} ({}): {} — will retry",
                        event.getId(), event.getEventType(), e.getMessage());
            }
        }
        if (published > 0) {
            log.info("Outbox relay published {}/{} event(s)", published, batch.size());
        }
    }

    private void publish(OutboxEvent event) {
        if (!webhookEnabled) {
            log.info("PUBLISH (log-only) [{}] {} -> {}",
                    event.getEventType(), event.getAggregateId(), event.getPayloadJson());
            return;
        }
        restClient.post()
                .header("X-Internal-Token", internalToken)
                .header("Content-Type", "application/json")
                .body(Map.of(
                        "eventId", event.getId(),
                        "eventType", event.getEventType(),
                        "payload", event.getPayloadJson()))
                .retrieve()
                .toBodilessEntity();
    }
}
