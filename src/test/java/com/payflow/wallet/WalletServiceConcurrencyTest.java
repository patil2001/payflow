package com.payflow.wallet;

import com.payflow.user.User;
import com.payflow.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the two properties money movement must have:
 *  1. No lost updates under concurrency (optimistic locking + retry).
 *  2. No double-spend on retried requests (idempotency).
 *
 * This test IS the interview demo — run it and show the invariants hold.
 */
@SpringBootTest
class WalletServiceConcurrencyTest {

    @Autowired WalletService walletService;
    @Autowired UserRepository users;
    @Autowired WalletRepository wallets;
    @Autowired LedgerEntryRepository ledger;

    Long alice, bob;

    @BeforeEach
    void setup() {
        alice = users.save(new User("alice+" + UUID.randomUUID() + "@test.com", "x")).getId();
        bob = users.save(new User("bob+" + UUID.randomUUID() + "@test.com", "x")).getId();
        walletService.createWallet(alice);  // opening balance 100_000
        walletService.createWallet(bob);
    }

    @Test
    void concurrentTransfersDoNotLoseUpdates() throws Exception {
        int threads = 10;
        long amount = 1_000L;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger succeeded = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    walletService.transfer(alice, bob, amount, UUID.randomUUID().toString());
                    succeeded.incrementAndGet();
                } catch (ConcurrentTransferException ignored) {
                    // acceptable: caller retries; money must never be lost either way
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        long aliceBalance = wallets.findByUserId(alice).orElseThrow().getBalanceMinor();
        long bobBalance = wallets.findByUserId(bob).orElseThrow().getBalanceMinor();

        // Conservation of money: total unchanged, and balances reflect EXACTLY
        // the number of successful transfers — no lost updates, no phantom money.
        assertEquals(200_000L, aliceBalance + bobBalance);
        assertEquals(100_000L - succeeded.get() * amount, aliceBalance);
        assertEquals(100_000L + succeeded.get() * amount, bobBalance);
    }

    @Test
    void sameIdempotencyKeyDoesNotDoubleSpend() {
        String key = UUID.randomUUID().toString();

        var first = walletService.transfer(alice, bob, 5_000L, key);
        var second = walletService.transfer(alice, bob, 5_000L, key);   // retry

        assertFalse(first.replayed());
        assertTrue(second.replayed());
        assertEquals(first.transferId(), second.transferId());

        // Only ONE debit happened
        assertEquals(95_000L, wallets.findByUserId(alice).orElseThrow().getBalanceMinor());
        assertEquals(105_000L, wallets.findByUserId(bob).orElseThrow().getBalanceMinor());
    }

    @Test
    void insufficientFundsRollsBackAtomically() {
        assertThrows(InsufficientFundsException.class, () ->
                walletService.transfer(alice, bob, 999_999L, UUID.randomUUID().toString()));

        // Nothing changed anywhere — transaction rolled back as a unit
        assertEquals(100_000L, wallets.findByUserId(alice).orElseThrow().getBalanceMinor());
        assertEquals(100_000L, wallets.findByUserId(bob).orElseThrow().getBalanceMinor());
    }

    @Test
    void ledgerEntriesBalanceToZero() {
        walletService.transfer(alice, bob, 2_500L, UUID.randomUUID().toString());

        long sum = ledger.findAll().stream().mapToLong(LedgerEntry::getAmountMinor).sum();
        assertEquals(0L, sum, "double-entry invariant: all ledger entries must net to zero");
    }
}
