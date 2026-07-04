package com.payflow.wallet;

public class ConcurrentTransferException extends RuntimeException {
    public ConcurrentTransferException() {
        super("Transfer failed due to concurrent updates, please retry");
    }
}
