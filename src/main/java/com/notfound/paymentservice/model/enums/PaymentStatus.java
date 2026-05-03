package com.notfound.paymentservice.model.enums;

import lombok.Getter;

@Getter
public enum PaymentStatus {
    COMPLETED, INCOMPLETED, PENDING, FAILED, REFUNDED
}
