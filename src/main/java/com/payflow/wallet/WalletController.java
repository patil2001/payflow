package com.payflow.wallet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/wallet")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    public record TransferRequest(@NotNull Long toUserId, @NotNull @Positive Long amountMinor) {}

    @GetMapping("/balance")
    public Map<String, Object> balance(@AuthenticationPrincipal Long userId) {
        return Map.of("userId", userId, "balanceMinor", walletService.getBalance(userId));
    }

    @GetMapping("/statement")
    public List<LedgerEntry> statement(@AuthenticationPrincipal Long userId) {
        return walletService.getStatement(userId);
    }

    /**
     * Idempotency-Key header is REQUIRED — this is how Stripe/Razorpay APIs work.
     * Retrying with the same key returns the original result (200 + replayed=true)
     * instead of moving money twice.
     */
    @PostMapping("/transfer")
    public ResponseEntity<WalletService.TransferResult> transfer(
            @AuthenticationPrincipal Long userId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferRequest req) {
        WalletService.TransferResult result =
                walletService.transfer(userId, req.toUserId(), req.amountMinor(), idempotencyKey);
        return ResponseEntity.ok(result);
    }
}
