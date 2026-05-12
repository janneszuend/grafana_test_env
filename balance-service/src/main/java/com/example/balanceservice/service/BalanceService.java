package com.example.balanceservice.service;

import com.example.balanceservice.model.Account;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BalanceService {

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final MeterRegistry meterRegistry;

    public BalanceService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        resetBalances();
    }

    public Account getAccount(String accountId) {
        return accounts.get(accountId);
    }

    public boolean credit(String accountId, double amount) {
        Account account = accounts.get(accountId);
        if (account == null) return false;
        account.credit(amount);
        return true;
    }

    public boolean debit(String accountId, double amount) {
        Account account = accounts.get(accountId);
        if (account == null) return false;
        return account.debit(amount);
    }

    public void resetBalances() {
        accounts.clear();
        accounts.put("acc-001", new Account("acc-001", "Alice", 1000.0));
        accounts.put("acc-002", new Account("acc-002", "Bob", 500.0));

        accounts.forEach((id, account) ->
            Gauge.builder("account.balance.chf", account, a -> a.getBalance())
                .tag("account_id", id)
                .tag("owner", account.getOwnerName())
                .register(meterRegistry)
        );
    }
}
