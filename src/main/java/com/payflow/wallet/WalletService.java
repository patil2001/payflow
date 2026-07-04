package com.payflow.wallet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.outbox.OutboxEvent;
import com.payflow.outbox.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);
    private static final int MAX_RETRIES = 3;

    private final WalletRepository wallets;
    private final LedgerEntryRepository ledger;
    private final IdempotencyRecordRepository idempotency;
    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;
    private final TransferTransaction transferTx;

    public WalletService(WalletRepository wallets, LedgerEntryRepository ledger,
                         IdempotencyRecordRepository idempotency, OutboxEventRepository outbox,
                         ObjectMapper objectMapper, TransferTransaction transferTx) {
        this.wallets = wallets;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.transferTx = transferTx;
    }

    public record TransferResult(String transferId, long amountMinor,
                                 long senderBalanceMinor, boolean replayed) {}

    @Transactional
    public Wallet createWallet(Long userId) {
        // Demo convenience: every new wallet starts with ₹1,000.00 (100000 paise)
        return wallets.save(new Wallet(userId, 100_000L));
    }

    /**
     * Money transfer with:
     *  1. Idempotency  — same (user, key) returns the stored result, never double-spends.
     *  2. Optimistic locking retry — concurrent transfers on the same wallet conflict on
     *     @Version; losers retry up to MAX_RETRIES with a fresh read.
     *
     * Retry lives OUTSIDE the transactional method: calling this.doTransfer() directly
     * would bypass the Spring proxy (self-invocation) and retries would run inside the
     * same aborted transaction. Hence the separate TransferTransaction bean.
     */
    public TransferResult transfer(Long fromUserId, Long toUserId, long amountMinor, String idempotencyKey) {
        if (amountMinor <= 0) throw new IllegalArgumentException("amount must be positive");
        if (fromUserId.equals(toUserId)) throw new IllegalArgumentException("cannot transfer to self");

        // Fast path: replay of a completed request
        var existing = idempotency.findByUserIdAndIdempotencyKey(fromUserId, idempotencyKey);
        if (existing.isPresent()) {
            return replayed(existing.get());
        }

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return transferTx.execute(fromUserId, toUserId, amountMinor, idempotencyKey);
            } catch (ObjectOptimisticLockingFailureException e) {
                log.warn("Optimistic lock conflict on transfer attempt {}/{} (from={} to={})",
                        attempt, MAX_RETRIES, fromUserId, toUserId);
                if (attempt == MAX_RETRIES) throw new ConcurrentTransferException();
            } catch (DataIntegrityViolationException e) {
                // Lost the idempotency INSERT race to a concurrent duplicate -> return its result
                return replayed(idempotency.findByUserIdAndIdempotencyKey(fromUserId, idempotencyKey)
                        .orElseThrow(() -> e));
            }
        }
        throw new ConcurrentTransferException(); // unreachable
    }

    private TransferResult replayed(IdempotencyRecord record) {
        try {
            TransferResult stored = objectMapper.readValue(record.getResponseJson(), TransferResult.class);
            return new TransferResult(stored.transferId(), stored.amountMinor(),
                    stored.senderBalanceMinor(), true);
        } catch (Exception e) {
            throw new IllegalStateException("Corrupt idempotency record", e);
        }
    }

    @Cacheable(value = "balances", key = "#userId")
    @Transactional(readOnly = true)
    public long getBalance(Long userId) {
        return wallets.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException(userId))
                .getBalanceMinor();
    }

    @Transactional(readOnly = true)
    public java.util.List<LedgerEntry> getStatement(Long userId) {
        Wallet wallet = wallets.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException(userId));
        return ledger.findByWalletIdOrderByIdDesc(wallet.getId());
    }

    /**
     * The actual DB transaction, in its own bean so the retry loop above goes
     * through the Spring proxy (interview talking point: @Transactional self-invocation).
     */
    @Service
    public static class TransferTransaction {

        private final WalletRepository wallets;
        private final LedgerEntryRepository ledger;
        private final IdempotencyRecordRepository idempotency;
        private final OutboxEventRepository outbox;
        private final ObjectMapper objectMapper;

        public TransferTransaction(WalletRepository wallets, LedgerEntryRepository ledger,
                                   IdempotencyRecordRepository idempotency,
                                   OutboxEventRepository outbox, ObjectMapper objectMapper) {
            this.wallets = wallets;
            this.ledger = ledger;
            this.idempotency = idempotency;
            this.outbox = outbox;
            this.objectMapper = objectMapper;
        }

        @Transactional
        @CacheEvict(value = "balances", allEntries = true)
        public TransferResult execute(Long fromUserId, Long toUserId, long amountMinor, String idempotencyKey) {
            Wallet from = wallets.findByUserId(fromUserId)
                    .orElseThrow(() -> new WalletNotFoundException(fromUserId));
            Wallet to = wallets.findByUserId(toUserId)
                    .orElseThrow(() -> new WalletNotFoundException(toUserId));

            from.debit(amountMinor);   // throws InsufficientFundsException -> rollback
            to.credit(amountMinor);

            String transferId = UUID.randomUUID().toString();

            // Double-entry: two rows, one transferId, net zero.
            ledger.save(new LedgerEntry(transferId, from.getId(), LedgerEntry.Direction.DEBIT,
                    amountMinor, from.getBalanceMinor()));
            ledger.save(new LedgerEntry(transferId, to.getId(), LedgerEntry.Direction.CREDIT,
                    amountMinor, to.getBalanceMinor()));

            TransferResult result = new TransferResult(transferId, amountMinor,
                    from.getBalanceMinor(), false);

            try {
                String json = objectMapper.writeValueAsString(result);
                // Same-transaction writes: idempotency record + outbox event.
                // All-or-nothing with the balance change — no dual-write problem.
                idempotency.save(new IdempotencyRecord(fromUserId, idempotencyKey, transferId, json));
                outbox.save(new OutboxEvent("Transfer", transferId, "TRANSFER_COMPLETED",
                        objectMapper.writeValueAsString(Map.of(
                                "transferId", transferId,
                                "fromUserId", fromUserId,
                                "toUserId", toUserId,
                                "amountMinor", amountMinor))));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }

            return result;
        }
    }
}
