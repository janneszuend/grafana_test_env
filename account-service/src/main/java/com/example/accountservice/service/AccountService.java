package com.example.accountservice.service;

import com.example.accountservice.model.Transaction;
import com.example.accountservice.model.TransactionRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final RestTemplate balanceRestTemplate;
    private final RestTemplate notificationRestTemplate;
    private final String balanceUrl;
    private final String notificationUrl;
    private final Counter transactionsCompleted;
    private final Counter transactionsFailedInsufficientFunds;
    private final Counter transactionsFailedTimeout;
    private final Counter transactionsFailedNotification;
    private final Timer balanceCheckDuration;
    private final Map<String, Transaction> transactions = new ConcurrentHashMap<>();

    public AccountService(
            @Qualifier("balanceRestTemplate") RestTemplate balanceRestTemplate,
            @Qualifier("notificationRestTemplate") RestTemplate notificationRestTemplate,
            @Value("${services.balance.url}") String balanceUrl,
            @Value("${services.notification.url}") String notificationUrl,
            MeterRegistry meterRegistry) {
        this.balanceRestTemplate = balanceRestTemplate;
        this.notificationRestTemplate = notificationRestTemplate;
        this.balanceUrl = balanceUrl;
        this.notificationUrl = notificationUrl;

        this.transactionsCompleted = Counter.builder("transactions.completed.total")
                .description("Total completed transactions").register(meterRegistry);
        this.transactionsFailedInsufficientFunds = Counter.builder("transactions.failed.total")
                .tag("reason", "insufficient_funds").register(meterRegistry);
        this.transactionsFailedTimeout = Counter.builder("transactions.failed.total")
                .tag("reason", "timeout").register(meterRegistry);
        this.transactionsFailedNotification = Counter.builder("transactions.failed.total")
                .tag("reason", "notification_error").register(meterRegistry);
        this.balanceCheckDuration = Timer.builder("balance.check.duration")
                .description("Duration of balance check from account service").register(meterRegistry);
    }

    public Transaction createTransaction(TransactionRequest request, boolean simulateDelay, boolean simulateNotificationError) {
        String transactionId = UUID.randomUUID().toString();
        String type = request.getType() != null ? request.getType().toUpperCase() : "TRANSFER";
        Transaction tx = new Transaction(transactionId, type, request.getFromAccountId(), request.getToAccountId(), request.getAmount());
        tx.setTraceId(MDC.get("trace_id"));

        log.info("Processing transaction: transactionId={}, type={}, amount={} CHF",
                transactionId, type, request.getAmount());

        if ("DEPOSIT".equals(type)) {
            return processDeposit(tx, simulateNotificationError);
        } else {
            return processTransfer(tx, simulateDelay, simulateNotificationError);
        }
    }

    private Transaction processDeposit(Transaction tx, boolean simulateNotificationError) {
        try {
            balanceRestTemplate.postForObject(
                    balanceUrl + "/api/balance/" + tx.getToAccountId() + "/credit",
                    Map.of("amount", tx.getAmount(), "transactionId", tx.getTransactionId()),
                    Map.class);
        } catch (Exception e) {
            log.error("Deposit credit failed for transactionId={}: {}", tx.getTransactionId(), e.getMessage());
            transactionsFailedTimeout.increment();
            tx.setStatus("FAILED");
            transactions.put(tx.getTransactionId(), tx);
            throw new TransactionException("Balance service unavailable", 503);
        }

        sendNotification(tx, simulateNotificationError);
        tx.setStatus("COMPLETED");
        transactions.put(tx.getTransactionId(), tx);
        transactionsCompleted.increment();
        log.info("Deposit completed: transactionId={}, toAccountId={}, amount={} CHF",
                tx.getTransactionId(), tx.getToAccountId(), tx.getAmount());
        return tx;
    }

    private Transaction processTransfer(Transaction tx, boolean simulateDelay, boolean simulateNotificationError) {
        // Step 1: Check balance
        Map<String, Object> balanceResponse;
        try {
            balanceResponse = balanceCheckDuration.record(() -> {
                String url = balanceUrl + "/api/balance/" + tx.getFromAccountId();
                if (simulateDelay) url += "?simulateDelay=true";
                @SuppressWarnings("unchecked")
                Map<String, Object> result = balanceRestTemplate.getForObject(url, Map.class);
                return result;
            });
        } catch (Exception e) {
            log.error("Balance check failed for transactionId={}: {}", tx.getTransactionId(), e.getMessage());
            transactionsFailedTimeout.increment();
            tx.setStatus("FAILED");
            transactions.put(tx.getTransactionId(), tx);
            throw new TransactionException("Balance service unavailable", 503);
        }

        double balance = balanceResponse != null ? ((Number) balanceResponse.get("balance")).doubleValue() : 0.0;
        if (balance < tx.getAmount()) {
            log.warn("Insufficient funds: transactionId={}, fromAccountId={}, balance={}, requested={}",
                    tx.getTransactionId(), tx.getFromAccountId(), balance, tx.getAmount());
            transactionsFailedInsufficientFunds.increment();
            tx.setStatus("REJECTED");
            transactions.put(tx.getTransactionId(), tx);
            throw new InsufficientFundsException(tx.getFromAccountId(), balance, tx.getAmount());
        }

        // Step 2: Debit from account
        try {
            balanceRestTemplate.postForObject(
                    balanceUrl + "/api/balance/" + tx.getFromAccountId() + "/debit",
                    Map.of("amount", tx.getAmount(), "transactionId", tx.getTransactionId()),
                    Map.class);
        } catch (HttpClientErrorException.Conflict e) {
            log.warn("Insufficient funds on debit: transactionId={}, fromAccountId={}",
                    tx.getTransactionId(), tx.getFromAccountId());
            transactionsFailedInsufficientFunds.increment();
            tx.setStatus("REJECTED");
            transactions.put(tx.getTransactionId(), tx);
            throw new InsufficientFundsException(tx.getFromAccountId(), 0, tx.getAmount());
        } catch (Exception e) {
            log.error("Debit failed for transactionId={}: {}", tx.getTransactionId(), e.getMessage());
            transactionsFailedTimeout.increment();
            tx.setStatus("FAILED");
            transactions.put(tx.getTransactionId(), tx);
            throw new TransactionException("Balance service unavailable during debit", 503);
        }

        // Step 3: Credit to account
        try {
            balanceRestTemplate.postForObject(
                    balanceUrl + "/api/balance/" + tx.getToAccountId() + "/credit",
                    Map.of("amount", tx.getAmount(), "transactionId", tx.getTransactionId()),
                    Map.class);
        } catch (Exception e) {
            log.warn("Credit to recipient failed for transactionId={}: {}", tx.getTransactionId(), e.getMessage());
        }

        // Step 4: Notify
        sendNotification(tx, simulateNotificationError);
        tx.setStatus("COMPLETED");
        transactions.put(tx.getTransactionId(), tx);
        transactionsCompleted.increment();
        log.info("Transfer completed: transactionId={}, fromAccountId={}, toAccountId={}, amount={} CHF",
                tx.getTransactionId(), tx.getFromAccountId(), tx.getToAccountId(), tx.getAmount());
        return tx;
    }

    private void sendNotification(Transaction tx, boolean simulateError) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (simulateError) {
                headers.set("X-Simulate-Error", "true");
            }
            String notifType = "DEPOSIT".equals(tx.getType()) ? "DEPOSIT_CONFIRMATION" : "TRANSFER_CONFIRMATION";
            HttpEntity<Map<String, String>> notifRequest = new HttpEntity<>(
                    Map.of("type", notifType,
                            "recipient", tx.getToAccountId() + "@bank.example",
                            "transactionId", tx.getTransactionId(),
                            "payload", tx.getType() + " of " + tx.getAmount() + " CHF completed"),
                    headers);
            notificationRestTemplate.postForObject(notificationUrl + "/api/notifications", notifRequest, Map.class);
        } catch (Exception e) {
            log.warn("Notification failed for transactionId={}: {}", tx.getTransactionId(), e.getMessage());
            transactionsFailedNotification.increment();
        }
    }

    public Transaction getTransaction(String transactionId) {
        return transactions.get(transactionId);
    }

    public void resetAccounts() {
        balanceRestTemplate.postForObject(balanceUrl + "/api/balance/reset", null, Map.class);
    }

    public static class InsufficientFundsException extends RuntimeException {
        private final String accountId;
        private final double balance;
        private final double requested;
        public InsufficientFundsException(String accountId, double balance, double requested) {
            super("Insufficient funds in account " + accountId);
            this.accountId = accountId;
            this.balance = balance;
            this.requested = requested;
        }
        public String getAccountId() { return accountId; }
        public double getBalance() { return balance; }
        public double getRequested() { return requested; }
    }

    public static class TransactionException extends RuntimeException {
        private final int status;
        public TransactionException(String message, int status) {
            super(message);
            this.status = status;
        }
        public int getStatus() { return status; }
    }
}
