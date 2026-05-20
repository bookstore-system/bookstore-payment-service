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
import com.notfound.paymentservice.service.VNPayService;
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
public class VNPayServiceImpl implements VNPayService {

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

    @Override
    @Transactional
    public CreatePaymentResponse createVNPayPaymentUrl(PaymentRequest request, HttpServletRequest httpServletRequest) {
        String transactionId = generateTransactionId();
        String paymentUrl = vnPayUtil.generatePaymentUrl(transactionId, request.getAmount(), httpServletRequest);
        savePayment(request, transactionId, paymentUrl);
        return createVNPayPaymentUrl(paymentUrl);
    }

    @Override
    @Transactional
    public CreatePaymentResponse createVNPayPaymentUrl(PaymentRequest request) {
        String transactionId = generateTransactionId();
        String paymentUrl = vnPayUtil.generatePaymentUrl(transactionId, request.getAmount(), "127.0.0.1");
        savePayment(request, transactionId, paymentUrl);
        return createVNPayPaymentUrl(paymentUrl);
    }

    private Payment savePayment(PaymentRequest request, String transactionId, String paymentUrl) {
        long amount = request.getAmount();

        Payment payment = Payment.builder()
                .orderId(request.getOrderId())
                .sagaId(request.getSagaId())
                .userId(request.getUserId())
                .amount(amount)
                .paymentMethod(PaymentMethod.VNPAY.name())
                .status(PaymentStatus.PENDING)
                .transactionId(transactionId)
                .redirectUrl(request.getRedirectUrl())
                .paymentUrl(paymentUrl)
                .date(LocalDateTime.now())
                .build();
        return paymentRepository.save(payment);
    }

    private CreatePaymentResponse createVNPayPaymentUrl(String paymentUrl) {
        return CreatePaymentResponse.builder()
                .code("200")
                .message("Successfully created VNPay payment URL")
                .paymentUrl(paymentUrl)
                .build();
    }

    @Override
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
            paymentMessageProducer.sendPaymentCompletedEvent(PaymentCompletedEvent.builder()
                    .sagaId(payment.getSagaId())
                    .orderId(payment.getOrderId())
                    .paymentId(payment.getPaymentID())
                    .userId(payment.getUserId())
                    .paymentMethod(payment.getPaymentMethod())
                    .status(PaymentStatus.COMPLETED.name())
                    .build());
        } else {
            payment.setStatus(PaymentStatus.FAILED);
            paymentMessageProducer.sendPaymentFailedEvent(PaymentCompletedEvent.builder()
                    .sagaId(payment.getSagaId())
                    .orderId(payment.getOrderId())
                    .paymentId(payment.getPaymentID())
                    .userId(payment.getUserId())
                    .paymentMethod(payment.getPaymentMethod())
                    .status(PaymentStatus.FAILED.name())
                    .build());
        }

        paymentRepository.save(payment);
        return mapToResponse(payment);
    }

    @Override
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
