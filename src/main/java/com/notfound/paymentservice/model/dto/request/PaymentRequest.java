package com.notfound.paymentservice.model.dto.request;

import lombok.*;
import lombok.experimental.FieldDefaults;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class PaymentRequest {
    UUID orderId;
    Long amount;
    String orderInfo;
    String bankCode;
    String redirectUrl;
}
