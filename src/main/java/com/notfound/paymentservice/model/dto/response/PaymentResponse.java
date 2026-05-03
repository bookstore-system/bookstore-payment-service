package com.notfound.paymentservice.model.dto.response;

import com.notfound.paymentservice.model.enums.PaymentStatus;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class PaymentResponse {
    UUID paymentId;
    UUID orderId;
    String paymentMethod;
    Double amount;
    LocalDateTime paymentDate;
    PaymentStatus status;
}
