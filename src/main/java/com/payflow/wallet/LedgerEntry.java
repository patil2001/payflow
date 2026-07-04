package com.payflow.wallet;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Double-entry ledger: every transfer writes exactly two rows -
 * a DEBIT on the sender and a CREDIT on the receiver, sharing one transferId.
 *
 * The ledger is APPEND-ONLY (no UPDATE/DELETE ever). Balances can always be
 * rebuilt by replaying the ledger; sum(all entries) must equal 0 across the
 * system — that invariant is what reconciliation jobs check in real fintech.
 */
@Entity
@Table(name = "ledger_entries", indexes = {
        @Index(name = "idx_ledger_wallet", columnList = "walletId"),
        @Index(name = "idx_ledger_transfer", columnList = "transferId")
})
public class LedgerEntry {

    public enum Direction { DEBIT, CREDIT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String transferId;

    @Column(nullable = false)
    private Long walletId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Direction direction;

    /** Signed amount in minor units: negative for DEBIT, positive for CREDIT. */
    @Column(nullable = false)
    private long amountMinor;

    /** Balance snapshot after applying this entry — makes statements O(1). */
    @Column(nullable = false)
    private long balanceAfterMinor;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected LedgerEntry() {}

    public LedgerEntry(String transferId, Long walletId, Direction direction,
                       long amountMinor, long balanceAfterMinor) {
        this.transferId = transferId;
        this.walletId = walletId;
        this.direction = direction;
        this.amountMinor = direction == Direction.DEBIT ? -Math.abs(amountMinor) : Math.abs(amountMinor);
        this.balanceAfterMinor = balanceAfterMinor;
    }

    public Long getId() { return id; }
    public String getTransferId() { return transferId; }
    public Long getWalletId() { return walletId; }
    public Direction getDirection() { return direction; }
    public long getAmountMinor() { return amountMinor; }
    public long getBalanceAfterMinor() { return balanceAfterMinor; }
    public Instant getCreatedAt() { return createdAt; }
}
