package com.notfound.paymentservice.service.impl;

import tools.jackson.databind.ObjectMapper;
import com.notfound.paymentservice.config.MoMoConfig;
import com.notfound.paymentservice.exception.AppException;
import com.notfound.paymentservice.exception.ErrorCode;
import com.notfound.paymentservice.model.dto.request.MoMoCallbackRequest;
import com.notfound.paymentservice.model.dto.request.PaymentRequest;
import com.notfound.paymentservice.model.dto.response.CreatePaymentResponse;
import com.notfound.paymentservice.model.dto.response.PaymentResponse;
import com.notfound.paymentservice.model.entity.Payment;
import com.notfound.paymentservice.model.enums.PaymentMethod;
import com.notfound.paymentservice.model.enums.PaymentStatus;
import com.notfound.paymentservice.repository.PaymentRepository;
import com.notfound.paymentservice.util.MoMoUtil;
import com.notfound.paymentservice.messaging.PaymentMessageProducer;
import com.notfound.paymentservice.messaging.PaymentCompletedEvent;
import com.notfound.paymentservice.service.MoMoService;
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
public class MoMoServiceImpl implements MoMoService {

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

        Integer resultCode = callbackRequest.getResultCode();
        if (resultCode != null && resultCode == 0) {
            payment.setStatus(PaymentStatus.COMPLETED);
            paymentMessageProducer.sendPaymentCompletedEvent(PaymentCompletedEvent.builder()
                    .orderId(payment.getOrderId())
                    .paymentId(payment.getPaymentID())
                    .paymentMethod(payment.getPaymentMethod())
                    .status(PaymentStatus.COMPLETED.name())
                    .build());
        } else {
            payment.setStatus(PaymentStatus.FAILED);
            paymentMessageProducer.sendPaymentFailedEvent(PaymentCompletedEvent.builder()
                    .orderId(payment.getOrderId())
                    .paymentId(payment.getPaymentID())
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
