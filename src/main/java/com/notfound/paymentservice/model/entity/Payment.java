package com.notfound.paymentservice.model.entity;

import com.notfound.paymentservice.model.enums.PaymentStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "payments")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
@FieldDefaults(level = AccessLevel.PRIVATE)
public class Payment {

    @Id
    @UuidGenerator
    UUID paymentID;

    @Column(name = "payment_method", nullable = false)
    String paymentMethod;

    @Column(nullable = false)
    Long amount;

    @CreationTimestamp
    @Column(name = "payment_date", nullable = false)
    LocalDateTime date;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    PaymentStatus status;

    String transactionId;

    @Column(name = "redirect_url", length = 500)
    String redirectUrl;

    @Column(name = "payment_url", length = 1024)
    String paymentUrl;

    @Column(name = "transaction_fee")
    Double transactionFee;

    @Column(name = "net_amount")
    Double netAmount;

    @Column(name = "order_id", nullable = false)
    UUID orderId;

    @Column(name = "saga_id", unique = true)
    UUID sagaId;

    @Column(name = "user_id")
    String userId;

    public Payment(String paymentMethod, Long amount, UUID orderId) {
        this.paymentMethod = paymentMethod;
        this.amount = amount;
        this.orderId = orderId;
        this.status = PaymentStatus.PENDING;
    }
}
