package com.payflow.notifications;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the consumer is idempotent: at-least-once delivery from the
 * publisher can NOT cause duplicate notifications.
 */
@SpringBootTest
class EventConsumerIdempotencyTest {

    @Autowired EventConsumerService consumer;
    @Autowired NotificationRepository notifications;

    private static final String PAYLOAD =
            "{\"transferId\":\"t-1\",\"fromUserId\":1,\"toUserId\":2,\"amountMinor\":5000}";

    @Test
    void duplicateDeliveryProducesOneSetOfNotifications() {
        boolean first = consumer.process(101L, "TRANSFER_COMPLETED", PAYLOAD);
        boolean second = consumer.process(101L, "TRANSFER_COMPLETED", PAYLOAD);  // redelivery

        assertTrue(first);
        assertFalse(second);

        // exactly one notification per party, not two
        assertEquals(1, notifications.findByUserIdOrderByIdDesc(1L).size());
        assertEquals(1, notifications.findByUserIdOrderByIdDesc(2L).size());
    }

    @Test
    void concurrentDuplicateDeliveriesAreProcessedExactlyOnce() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger processedCount = new AtomicInteger();

        String payload = "{\"transferId\":\"t-2\",\"fromUserId\":3,\"toUserId\":4,\"amountMinor\":1000}";
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (consumer.process(202L, "TRANSFER_COMPLETED", payload)) {
                        processedCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    // constraint-race losers may surface as exceptions depending on tx timing;
                    // the invariant below is what matters
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertTrue(processedCount.get() >= 1);
        assertEquals(1, notifications.findByUserIdOrderByIdDesc(3L).size(),
                "same event delivered by 8 threads must produce exactly one notification");
    }
}
