package com.example.accountservice.controller;

import com.example.accountservice.model.Transaction;
import com.example.accountservice.model.TransactionRequest;
import com.example.accountservice.service.AccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);
    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping("/transactions")
    public ResponseEntity<?> createTransaction(
            @RequestBody TransactionRequest request,
            @RequestParam(defaultValue = "false") boolean simulateDelay,
            @RequestHeader(value = "X-Simulate-Error", defaultValue = "false") boolean simulateNotificationError) {
        Transaction tx = accountService.createTransaction(request, simulateDelay, simulateNotificationError);
        return ResponseEntity.status(201).body(Map.of(
                "transactionId", tx.getTransactionId(),
                "type", tx.getType(),
                "status", tx.getStatus(),
                "amount", tx.getAmount(),
                "currency", tx.getCurrency(),
                "traceId", tx.getTraceId() != null ? tx.getTraceId() : ""
        ));
    }

    @GetMapping("/transactions/{id}")
    public ResponseEntity<?> getTransaction(@PathVariable String id) {
        Transaction tx = accountService.getTransaction(id);
        if (tx == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "transactionId", tx.getTransactionId(),
                "type", tx.getType(),
                "fromAccountId", tx.getFromAccountId() != null ? tx.getFromAccountId() : "",
                "toAccountId", tx.getToAccountId() != null ? tx.getToAccountId() : "",
                "amount", tx.getAmount(),
                "currency", tx.getCurrency(),
                "status", tx.getStatus(),
                "createdAt", tx.getCreatedAt().toString()
        ));
    }

    @PostMapping("/accounts/reset")
    public ResponseEntity<?> resetAccounts() {
        accountService.resetAccounts();
        log.info("Account balances reset triggered");
        return ResponseEntity.ok(Map.of("status", "RESET"));
    }

    @ExceptionHandler(AccountService.InsufficientFundsException.class)
    public ResponseEntity<?> handleInsufficientFunds(AccountService.InsufficientFundsException ex) {
        return ResponseEntity.status(409).body(Map.of(
                "error", "INSUFFICIENT_FUNDS",
                "accountId", ex.getAccountId(),
                "balance", ex.getBalance(),
                "requested", ex.getRequested(),
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(AccountService.TransactionException.class)
    public ResponseEntity<?> handleTransactionException(AccountService.TransactionException ex) {
        return ResponseEntity.status(ex.getStatus()).body(Map.of(
                "error", "TRANSACTION_FAILED",
                "message", ex.getMessage()
        ));
    }
}
