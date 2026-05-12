package com.example.balanceservice.controller;

import com.example.balanceservice.model.Account;
import com.example.balanceservice.service.BalanceService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@RestController
@RequestMapping("/api/balance")
public class BalanceController {

    private static final Logger log = LoggerFactory.getLogger(BalanceController.class);
    private final BalanceService balanceService;
    private final Timer checkDuration;

    public BalanceController(BalanceService balanceService, MeterRegistry meterRegistry) {
        this.balanceService = balanceService;
        this.checkDuration = Timer.builder("balance.check.duration")
                .description("Duration of balance checks")
                .register(meterRegistry);
    }

    @GetMapping("/{accountId}")
    public ResponseEntity<?> getBalance(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "false") boolean simulateDelay) {

        return checkDuration.record(() -> {
            if (simulateDelay) {
                try {
                    long delay = ThreadLocalRandom.current().nextLong(2000, 4001);
                    log.warn("Simulating slow balance check for account={}, delay={}ms", accountId, delay);
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            Account account = balanceService.getAccount(accountId);
            if (account == null) {
                log.warn("Account not found: {}", accountId);
                return ResponseEntity.notFound().build();
            }

            log.info("Balance check for account={}, balance={} CHF", accountId, account.getBalance());
            return ResponseEntity.ok(Map.of(
                    "accountId", account.getAccountId(),
                    "ownerName", account.getOwnerName(),
                    "balance", account.getBalance(),
                    "currency", "CHF"
            ));
        });
    }

    @PostMapping("/{accountId}/credit")
    public ResponseEntity<?> credit(@PathVariable String accountId, @RequestBody Map<String, Object> request) {
        double amount = ((Number) request.getOrDefault("amount", 0.0)).doubleValue();
        String transactionId = (String) request.getOrDefault("transactionId", "unknown");

        Account account = balanceService.getAccount(accountId);
        if (account == null) {
            return ResponseEntity.notFound().build();
        }

        balanceService.credit(accountId, amount);
        log.info("Credited {} CHF to account={}, transactionId={}, newBalance={} CHF",
                amount, accountId, transactionId, account.getBalance());
        return ResponseEntity.ok(Map.of(
                "accountId", accountId,
                "credited", amount,
                "balance", account.getBalance(),
                "transactionId", transactionId
        ));
    }

    @PostMapping("/{accountId}/debit")
    public ResponseEntity<?> debit(@PathVariable String accountId, @RequestBody Map<String, Object> request) {
        double amount = ((Number) request.getOrDefault("amount", 0.0)).doubleValue();
        String transactionId = (String) request.getOrDefault("transactionId", "unknown");

        Account account = balanceService.getAccount(accountId);
        if (account == null) {
            return ResponseEntity.notFound().build();
        }

        boolean debited = balanceService.debit(accountId, amount);
        if (!debited) {
            log.warn("Insufficient funds: account={}, transactionId={}, requested={} CHF, balance={} CHF",
                    accountId, transactionId, amount, account.getBalance());
            return ResponseEntity.status(409).body(Map.of(
                    "error", "INSUFFICIENT_FUNDS",
                    "accountId", accountId,
                    "balance", account.getBalance(),
                    "requested", amount
            ));
        }

        log.info("Debited {} CHF from account={}, transactionId={}, newBalance={} CHF",
                amount, accountId, transactionId, account.getBalance());
        return ResponseEntity.ok(Map.of(
                "accountId", accountId,
                "debited", amount,
                "balance", account.getBalance(),
                "transactionId", transactionId
        ));
    }

    @PostMapping("/reset")
    public ResponseEntity<?> reset() {
        balanceService.resetBalances();
        log.info("Account balances reset to initial values");
        return ResponseEntity.ok(Map.of("status", "RESET", "message", "Balances reset to initial values"));
    }
}
