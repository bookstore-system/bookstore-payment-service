package com.notfound.paymentservice.service.impl;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.stream.Collectors;

import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.json.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.notfound.paymentservice.config.ZaloPayConfig;
import com.notfound.paymentservice.exception.AppException;
import com.notfound.paymentservice.exception.ErrorCode;
import com.notfound.paymentservice.messaging.PaymentCompletedEvent;
import com.notfound.paymentservice.messaging.PaymentMessageProducer;
import com.notfound.paymentservice.model.dto.request.PaymentRequest;
import com.notfound.paymentservice.model.dto.request.ZaloPayCallbackRequest;
import com.notfound.paymentservice.model.dto.response.CreatePaymentResponse;
import com.notfound.paymentservice.model.entity.Payment;
import com.notfound.paymentservice.model.enums.PaymentMethod;
import com.notfound.paymentservice.model.enums.PaymentStatus;
import com.notfound.paymentservice.repository.PaymentRepository;
import com.notfound.paymentservice.service.ZaloPayService;
import com.notfound.paymentservice.util.HMACUtil;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class ZaloPayServiceImpl implements ZaloPayService {

    private final ZaloPayConfig zaloPayConfig;
    private final PaymentRepository paymentRepository;
    private final PaymentMessageProducer paymentMessageProducer;
    private final ObjectMapper objectMapper;

    private String getCurrentTimeString(String format) {
        Calendar cal = new GregorianCalendar(TimeZone.getTimeZone("GMT+7"));
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(format);
        fmt.setCalendar(cal);
        return fmt.format(cal.getTimeInMillis());
    }

    @Transactional
    public CreatePaymentResponse createOrderTransaction(PaymentRequest request) {
        try {
            long currentTimestamp = System.currentTimeMillis();
            String appTransId = getCurrentTimeString("yyMMdd") + "_" + System.currentTimeMillis();

            Payment payment = Payment.builder()
                    .orderId(request.getOrderId())
                    .amount(request.getAmount())
                    .paymentMethod(PaymentMethod.ZALOPAY.name())
                    .status(PaymentStatus.PENDING)
                    .transactionId(appTransId)
                    .redirectUrl(request.getRedirectUrl())
                    .date(LocalDateTime.now())
                    .build();
            paymentRepository.save(payment);

            String orderInfo = "Thanh toán ZaloPay cho đơn hàng: " + request.getOrderId().toString();
            boolean tpeEndpoint = isTpeEndpoint(zaloPayConfig.getEndpoint());
            Map<String, Object> order = buildCreateOrderRequest(appTransId, currentTimestamp, request.getAmount(),
                    orderInfo, tpeEndpoint);

            String data = buildMacData(order, tpeEndpoint);
            order.put("mac", HMACUtil.HMacHexStringEncode(HMACUtil.HMACSHA256, zaloPayConfig.getKey1(), data));

            JSONObject jsonResult;
            try (CloseableHttpClient client = HttpClients.createDefault()) {
                HttpPost post = new HttpPost(zaloPayConfig.getEndpoint());

                List<NameValuePair> params = new ArrayList<>();
                for (Map.Entry<String, Object> e : order.entrySet()) {
                    params.add(new BasicNameValuePair(e.getKey(), e.getValue().toString()));
                }
                post.setEntity(new UrlEncodedFormEntity(params, StandardCharsets.UTF_8));

                try (CloseableHttpResponse res = client.execute(post);
                        BufferedReader rd = new BufferedReader(
                                new InputStreamReader(res.getEntity().getContent(), StandardCharsets.UTF_8))) {
                    String resultJsonStr = rd.lines().collect(Collectors.joining());
                    jsonResult = new JSONObject(resultJsonStr);
                }
            }

            int returnCode = getInt(jsonResult, "return_code", "returncode");
            String returnMessage = getString(jsonResult, "return_message", "returnmessage");
            log.info("ZaloPay response: returnCode={}, returnMessage={}", returnCode, returnMessage);

            if (returnCode == 1) {
                return CreatePaymentResponse.builder()
                        .code("200")
                        .message("Successfully created ZaloPay payment URL")
                        .paymentUrl(getString(jsonResult, "order_url", "orderurl"))
                        .build();
            } else {
                log.warn("ZaloPay rejected create order: code={}, message={}", returnCode, returnMessage);
                throw new AppException(ErrorCode.PAYMENT_FAILED);
            }
        } catch (Exception e) {
            log.error("Failed to create ZaloPay transaction", e);
            throw new AppException(ErrorCode.PAYMENT_FAILED);
        }
    }

    private boolean isTpeEndpoint(String endpoint) {
        return endpoint != null && endpoint.contains("/tpe/");
    }

    private Map<String, Object> buildCreateOrderRequest(
            String appTransId,
            long currentTimestamp,
            Long amount,
            String orderInfo,
            boolean tpeEndpoint) {
        Map<String, Object> order = new HashMap<>();
        String embedData = "{\"redirecturl\": \"" + zaloPayConfig.getReturnUrl() + "\"}";
        String item = "[]";

        if (tpeEndpoint) {
            order.put("appid", zaloPayConfig.getAppId());
            order.put("apptransid", appTransId);
            order.put("apptime", currentTimestamp);
            order.put("appuser", "Bookstore User");
            order.put("amount", amount);
            order.put("description", orderInfo);
            order.put("bankcode", "zalopayapp");
            order.put("item", item);
            order.put("embeddata", embedData);
            order.put("callbackurl", zaloPayConfig.getCallbackUrl());
            return order;
        }

        order.put("app_id", zaloPayConfig.getAppId());
        order.put("app_trans_id", appTransId);
        order.put("app_time", currentTimestamp);
        order.put("app_user", "Bookstore User");
        order.put("amount", amount);
        order.put("description", orderInfo);
        order.put("bank_code", "zalopayapp");
        order.put("item", item);
        order.put("embed_data", embedData);
        order.put("callback_url", zaloPayConfig.getCallbackUrl());
        return order;
    }

    private String buildMacData(Map<String, Object> order, boolean tpeEndpoint) {
        if (tpeEndpoint) {
            return order.get("appid") + "|" + order.get("apptransid") + "|" + order.get("appuser") + "|"
                    + order.get("amount") + "|" + order.get("apptime") + "|" + order.get("embeddata") + "|"
                    + order.get("item");
        }

        return order.get("app_id") + "|" + order.get("app_trans_id") + "|" + order.get("app_user") + "|"
                + order.get("amount") + "|" + order.get("app_time") + "|" + order.get("embed_data") + "|"
                + order.get("item");
    }

    private int getInt(JSONObject json, String primaryKey, String fallbackKey) {
        if (json.has(primaryKey)) {
            return json.optInt(primaryKey);
        }
        return json.optInt(fallbackKey);
    }

    private String getString(JSONObject json, String primaryKey, String fallbackKey) {
        if (json.has(primaryKey)) {
            return json.optString(primaryKey);
        }
        return json.optString(fallbackKey);
    }

    @Override
    public String getRedirectUrlByTransactionId(String transactionId) {
        return paymentRepository.findByTransactionId(transactionId)
                .map(Payment::getRedirectUrl)
                .orElse(null);
    }

    @Override
    @Transactional
    public void markPaymentCompleted(String appTransId) {
        if (appTransId == null || appTransId.isEmpty()) {
            return;
        }
        paymentRepository.findByTransactionId(appTransId).ifPresent(payment -> {
            if (payment.getStatus() != PaymentStatus.PENDING) {
                return;
            }
            payment.setStatus(PaymentStatus.COMPLETED);
            paymentRepository.save(payment);
            paymentMessageProducer.sendPaymentCompletedEvent(PaymentCompletedEvent.builder()
                    .orderId(payment.getOrderId())
                    .paymentId(payment.getPaymentID())
                    .paymentMethod(payment.getPaymentMethod())
                    .status(PaymentStatus.COMPLETED.name())
                    .build());
        });
    }

    @Override
    @Transactional
    public void markPaymentFailed(String appTransId) {
        if (appTransId == null || appTransId.isEmpty()) {
            return;
        }
        paymentRepository.findByTransactionId(appTransId).ifPresent(payment -> {
            if (payment.getStatus() != PaymentStatus.PENDING) {
                return;
            }
            payment.setStatus(PaymentStatus.FAILED);
            paymentRepository.save(payment);
            paymentMessageProducer.sendPaymentFailedEvent(PaymentCompletedEvent.builder()
                    .orderId(payment.getOrderId())
                    .paymentId(payment.getPaymentID())
                    .paymentMethod(payment.getPaymentMethod())
                    .status(PaymentStatus.FAILED.name())
                    .build());
        });
    }

    @Transactional
    public boolean handleCallback(ZaloPayCallbackRequest cbData) {
        try {
            String dataStr = cbData.getData();
            String reqMac = cbData.getMac();
            String mac = HMACUtil.HMacHexStringEncode(HMACUtil.HMACSHA256, zaloPayConfig.getKey2(), dataStr);

            if (!reqMac.equals(mac)) {
                log.error("Invalid MAC");
                throw new AppException(ErrorCode.INVALID_SIGNATURE);
            }

            JSONObject dataObj = new JSONObject(dataStr);
            String appTransId = getString(dataObj, "app_trans_id", "apptransid");

            Payment payment = paymentRepository.findByTransactionId(appTransId)
                    .orElseThrow(() -> new AppException(ErrorCode.PAYMENT_NOT_FOUND));

            if (payment.getStatus() != PaymentStatus.PENDING) {
                return true;
            }

            payment.setStatus(PaymentStatus.COMPLETED);
            paymentMessageProducer.sendPaymentCompletedEvent(
                    PaymentCompletedEvent.builder().orderId(payment.getOrderId()).paymentId(payment.getPaymentID())
                            .paymentMethod(payment.getPaymentMethod()).status(PaymentStatus.COMPLETED.name()).build());
            paymentRepository.save(payment);
            return true;

        } catch (Exception ex) {
            log.error("Error processing ZaloPay callback", ex);
            return false;
        }
    }
}
