package com.payflow.wallet;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(Long userId, long balance, long requested) {
        super("Insufficient funds: balance=" + balance + " requested=" + requested);
    }
}
