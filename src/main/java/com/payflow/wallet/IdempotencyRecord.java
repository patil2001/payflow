package com.payflow.wallet;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Stores the response of a completed request keyed by the client-supplied
 * Idempotency-Key. A retried request (timeout, network blip, user double-click)
 * returns the SAME result instead of moving money twice.
 *
 * The UNIQUE constraint on (userId, idempotencyKey) is the real guard:
 * two concurrent requests with the same key race, one INSERT wins,
 * the loser gets a constraint violation and re-reads the stored response.
 */
@Entity
@Table(name = "idempotency_records", uniqueConstraints =
        @UniqueConstraint(name = "uq_idem_user_key", columnNames = {"userId", "idempotencyKey"}))
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String idempotencyKey;

    @Column(nullable = false)
    private String transferId;

    @Column(nullable = false, length = 2000)
    private String responseJson;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected IdempotencyRecord() {}

    public IdempotencyRecord(Long userId, String idempotencyKey, String transferId, String responseJson) {
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.transferId = transferId;
        this.responseJson = responseJson;
    }

    public String getTransferId() { return transferId; }
    public String getResponseJson() { return responseJson; }
}
