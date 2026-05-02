package com.hamtech.bookstorepaymentservice.exception;

import lombok.Getter;

@Getter
public enum ErrorCode {
    ORDER_NOT_FOUND(404, "Order not found"),
    PAYMENT_FAILED(400, "Payment failed"),
    ERROR_CREATE_HMACSHA512(500, "Error creating HMAC_SHA512"),
    INVALID_SIGNATURE(400, "Invalid signature"),
    PAYMENT_NOT_FOUND(404, "Payment not found"),
    UNCATEGORIZED_EXCEPTION(500, "Uncategorized exception");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
