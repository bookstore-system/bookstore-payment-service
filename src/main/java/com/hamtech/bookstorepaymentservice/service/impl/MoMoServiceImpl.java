package com.hamtech.bookstorepaymentservice.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamtech.bookstorepaymentservice.config.MoMoConfig;
import com.hamtech.bookstorepaymentservice.exception.AppException;
import com.hamtech.bookstorepaymentservice.exception.ErrorCode;
import com.hamtech.bookstorepaymentservice.model.dto.request.MoMoCallbackRequest;
import com.hamtech.bookstorepaymentservice.model.dto.request.PaymentRequest;
import com.hamtech.bookstorepaymentservice.model.dto.response.CreatePaymentResponse;
import com.hamtech.bookstorepaymentservice.model.dto.response.PaymentResponse;
import com.hamtech.bookstorepaymentservice.model.entity.Payment;
import com.hamtech.bookstorepaymentservice.model.enums.PaymentMethod;
import com.hamtech.bookstorepaymentservice.model.enums.PaymentStatus;
import com.hamtech.bookstorepaymentservice.repository.PaymentRepository;
import com.hamtech.bookstorepaymentservice.util.MoMoUtil;
import com.hamtech.bookstorepaymentservice.messaging.PaymentMessageProducer;
import com.hamtech.bookstorepaymentservice.messaging.PaymentCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MoMoServiceImpl {

    private final MoMoConfig moMoConfig;
    private final MoMoUtil moMoUtil;
    private final PaymentRepository paymentRepository;
    private final PaymentMessageProducer paymentMessageProducer;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public CreatePaymentResponse createMoMoPayment(PaymentRequest request) {
        try {
            String partnerCode = moMoConfig.getPartnerCode();
            String accessKey = moMoConfig.getAccessKey();
            String secretKey = moMoConfig.getSecretKey();
            String returnUrl = moMoConfig.getReturnUrl();
            String notifyUrl = moMoConfig.getIpnUrl();
            String endpoint = moMoConfig.getEndpoint();
            
            String orderId = request.getOrderId().toString() + "-" + System.currentTimeMillis();
            String requestId = String.valueOf(System.currentTimeMillis());
            String orderInfo = "Thanh toán qua MoMo cho mã đơn " + request.getOrderId();
            String amount = String.valueOf(request.getAmount());
            String requestType = "captureWallet";
            String extraData = "";

            Payment payment = Payment.builder()
                    .orderId(request.getOrderId())
                    .amount(request.getAmount())
                    .paymentMethod(PaymentMethod.MOMO.name())
                    .status(PaymentStatus.PENDING)
                    .transactionId(orderId)
                    .redirectUrl(request.getRedirectUrl())
                    .date(LocalDateTime.now())
                    .build();
            paymentRepository.save(payment);

            String rawData = "accessKey=" + accessKey
                    + "&amount=" + amount
                    + "&extraData=" + extraData
                    + "&ipnUrl=" + notifyUrl
                    + "&orderId=" + orderId
                    + "&orderInfo=" + orderInfo
                    + "&partnerCode=" + partnerCode
                    + "&redirectUrl=" + returnUrl
                    + "&requestId=" + requestId
                    + "&requestType=" + requestType;

            String signature = moMoUtil.generateSignature(rawData);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("partnerCode", partnerCode);
            requestBody.put("partnerName", "Test");
            requestBody.put("storeId", "MomoTestStore");
            requestBody.put("requestId", requestId);
            requestBody.put("amount", amount);
            requestBody.put("orderId", orderId);
            requestBody.put("orderInfo", orderInfo);
            requestBody.put("redirectUrl", returnUrl);
            requestBody.put("ipnUrl", notifyUrl);
            requestBody.put("lang", "vi");
            requestBody.put("extraData", extraData);
            requestBody.put("requestType", requestType);
            requestBody.put("signature", signature);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);
            ResponseEntity<String> response = restTemplate.postForEntity(endpoint, entity, String.class);

            Map<String, Object> jsonResponse = objectMapper.readValue(response.getBody(), Map.class);

            if (jsonResponse.containsKey("payUrl") && jsonResponse.get("payUrl") != null) {
                return CreatePaymentResponse.builder()
                        .code("200")
                        .message("Successfully created MoMo payment URL")
                        .paymentUrl((String) jsonResponse.get("payUrl"))
                        .build();
            } else {
                throw new AppException(ErrorCode.PAYMENT_FAILED);
            }

        } catch (Exception e) {
            log.error("Failed to create MoMo payment", e);
            throw new AppException(ErrorCode.PAYMENT_FAILED);
        }
    }

    @Transactional
    public PaymentResponse handleMoMoCallback(MoMoCallbackRequest callbackRequest) {
        String rawData = "accessKey=" + moMoConfig.getAccessKey()
                + "&amount=" + callbackRequest.getAmount()
                + "&extraData=" + callbackRequest.getExtraData()
                + "&message=" + callbackRequest.getMessage()
                + "&orderId=" + callbackRequest.getOrderId()
                + "&orderInfo=" + callbackRequest.getOrderInfo()
                + "&orderType=" + callbackRequest.getOrderType()
                + "&partnerCode=" + callbackRequest.getPartnerCode()
                + "&payType=" + callbackRequest.getPayType()
                + "&requestId=" + callbackRequest.getRequestId()
                + "&responseTime=" + callbackRequest.getResponseTime()
                + "&resultCode=" + callbackRequest.getResultCode()
                + "&transId=" + callbackRequest.getTransId();

        if (!moMoUtil.verifySignature(rawData, callbackRequest.getSignature())) {
            throw new AppException(ErrorCode.INVALID_SIGNATURE);
        }

        Payment payment = paymentRepository.findByTransactionId(callbackRequest.getOrderId())
                .orElseThrow(() -> new AppException(ErrorCode.PAYMENT_NOT_FOUND));

        if (payment.getStatus() != PaymentStatus.PENDING) {
            return mapToResponse(payment);
        }

        if (callbackRequest.getResultCode() == 0) {
            payment.setStatus(PaymentStatus.COMPLETED);
            paymentMessageProducer.sendPaymentCompletedEvent(PaymentCompletedEvent.builder().orderId(payment.getOrderId()).paymentId(payment.getPaymentID()).paymentMethod(payment.getPaymentMethod()).status(PaymentStatus.COMPLETED.name()).build());
        } else {
            payment.setStatus(PaymentStatus.FAILED);
        }

        paymentRepository.save(payment);
        return mapToResponse(payment);
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
