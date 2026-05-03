package com.notfound.paymentservice.model.dto.response;

import lombok.*;
import lombok.experimental.FieldDefaults;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class CreatePaymentResponse {
    String code;
    String message;
    String paymentUrl;
    PaymentResponse payment;
}
