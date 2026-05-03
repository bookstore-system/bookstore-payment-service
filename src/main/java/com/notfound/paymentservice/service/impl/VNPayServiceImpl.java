package com.notfound.paymentservice.service.impl;

import com.notfound.paymentservice.config.VNPayConfig;
import com.notfound.paymentservice.exception.AppException;
import com.notfound.paymentservice.exception.ErrorCode;
import com.notfound.paymentservice.model.dto.request.PaymentRequest;
import com.notfound.paymentservice.model.dto.request.VNPayCallbackRequest;
import com.notfound.paymentservice.model.dto.response.CreatePaymentResponse;
import com.notfound.paymentservice.model.dto.response.PaymentResponse;
import com.notfound.paymentservice.model.entity.Payment;
import com.notfound.paymentservice.model.enums.PaymentMethod;
import com.notfound.paymentservice.model.enums.PaymentStatus;
import com.notfound.paymentservice.repository.PaymentRepository;
import com.notfound.paymentservice.util.VNPayUtil;
import com.notfound.paymentservice.messaging.PaymentMessageProducer;
import com.notfound.paymentservice.messaging.PaymentCompletedEvent;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.security.SecureRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class VNPayServiceImpl {

    private final VNPayConfig vnPayConfig;
    private final VNPayUtil vnPayUtil;
    private final PaymentRepository paymentRepository;
    private final PaymentMessageProducer paymentMessageProducer;

    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom random = new SecureRandom();

    public static String generateTransactionId() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            int index = random.nextInt(ALPHANUMERIC.length());
            sb.append(ALPHANUMERIC.charAt(index));
        }
        return sb.toString();
    }

    @Transactional
    public CreatePaymentResponse createVNPayPaymentUrl(PaymentRequest request, HttpServletRequest httpServletRequest) {
        String transactionId = generateTransactionId();
        long amount = request.getAmount();

        Payment payment = Payment.builder()
                .orderId(request.getOrderId())
                .amount(amount)
                .paymentMethod(PaymentMethod.VNPAY.name())
                .status(PaymentStatus.PENDING)
                .transactionId(transactionId)
                .redirectUrl(request.getRedirectUrl())
                .date(LocalDateTime.now())
                .build();
        paymentRepository.save(payment);

        String paymentUrl = vnPayUtil.generatePaymentUrl(transactionId, amount, httpServletRequest);

        return CreatePaymentResponse.builder()
                .code("200")
                .message("Successfully created VNPay payment URL")
                .paymentUrl(paymentUrl)
                .build();
    }

    @Transactional
    public PaymentResponse handleVNPayReturn(VNPayCallbackRequest vnpParamsRequest) {
        if (!vnPayUtil.verifyReturnDataSignature(vnpParamsRequest)) {
            throw new AppException(ErrorCode.INVALID_SIGNATURE);
        }

        String transactionId = vnpParamsRequest.getVnp_TxnRef();
        Payment payment = paymentRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> new AppException(ErrorCode.PAYMENT_NOT_FOUND));

        if (payment.getStatus() != PaymentStatus.PENDING) {
            log.info("Payment already processed");
            return mapToResponse(payment);
        }

        if (vnpParamsRequest.isSuccess()) {
            payment.setStatus(PaymentStatus.COMPLETED);
            paymentMessageProducer.sendPaymentCompletedEvent(PaymentCompletedEvent.builder().orderId(payment.getOrderId()).paymentId(payment.getPaymentID()).paymentMethod(payment.getPaymentMethod()).status(PaymentStatus.COMPLETED.name()).build());
        } else {
            payment.setStatus(PaymentStatus.FAILED);
        }

        paymentRepository.save(payment);
        return mapToResponse(payment);
    }

    public String getRedirectUrlByTransactionId(String transactionId) {
        return paymentRepository.findByTransactionId(transactionId)
                .map(Payment::getRedirectUrl)
                .orElse(null);
    }
    
    private PaymentResponse mapToResponse(Payment payment) {
        return PaymentResponse.builder()
                .paymentId(payment.getPaymentID())
                .orderId(payment.getOrderId())
                .paymentMethod(payment.getPaymentMethod())
                .amount(payment.getAmount() != null ? payment.getAmount().doubleValue() : 0.0)
                .paymentDate(payment.getDate())
                .status(payment.getStatus())
                .build();
    }
}
