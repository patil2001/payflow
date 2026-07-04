package com.payflow.wallet;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Balance is stored in minor units (paise/cents) as a long — never floating point for money.
 *
 * @Version enables OPTIMISTIC LOCKING: every UPDATE runs as
 *   UPDATE wallet SET balance=?, version=version+1 WHERE id=? AND version=?
 * If another transaction committed first, 0 rows match -> ObjectOptimisticLockingFailureException
 * -> we retry. This is how you prevent lost updates on concurrent transfers
 * without holding pessimistic row locks.
 */
@Entity
@Table(name = "wallets", indexes = @Index(name = "idx_wallet_user", columnList = "userId", unique = true))
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long userId;

    @Column(nullable = false)
    private long balanceMinor;

    @Version
    private long version;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected Wallet() {}

    public Wallet(Long userId, long openingBalanceMinor) {
        this.userId = userId;
        this.balanceMinor = openingBalanceMinor;
    }

    public void credit(long amountMinor) {
        this.balanceMinor += amountMinor;
    }

    public void debit(long amountMinor) {
        if (balanceMinor < amountMinor) {
            throw new InsufficientFundsException(userId, balanceMinor, amountMinor);
        }
        this.balanceMinor -= amountMinor;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public long getBalanceMinor() { return balanceMinor; }
    public long getVersion() { return version; }
}
