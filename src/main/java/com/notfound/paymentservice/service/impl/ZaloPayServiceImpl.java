package com.notfound.paymentservice.service.impl;

import tools.jackson.databind.ObjectMapper;
import com.notfound.paymentservice.config.ZaloPayConfig;
import com.notfound.paymentservice.exception.AppException;
import com.notfound.paymentservice.exception.ErrorCode;
import com.notfound.paymentservice.model.dto.request.PaymentRequest;
import com.notfound.paymentservice.model.dto.request.ZaloPayCallbackRequest;
import com.notfound.paymentservice.model.dto.response.CreatePaymentResponse;
import com.notfound.paymentservice.model.entity.Payment;
import com.notfound.paymentservice.model.enums.PaymentMethod;
import com.notfound.paymentservice.model.enums.PaymentStatus;
import com.notfound.paymentservice.repository.PaymentRepository;
import com.notfound.paymentservice.util.HMACUtil;
import com.notfound.paymentservice.messaging.PaymentMessageProducer;
import com.notfound.paymentservice.messaging.PaymentCompletedEvent;
import com.notfound.paymentservice.service.ZaloPayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.*;

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
            Map<String, Object> order = new HashMap<String, Object>() {{
                put("app_id", zaloPayConfig.getAppId());
                put("app_trans_id", appTransId);
                put("app_time", currentTimestamp);
                put("app_user", "Bookstore User");
                put("amount", request.getAmount());
                put("description", orderInfo);
                put("bank_code", "zalopayapp");
                put("item", "[]");
                put("embed_data", "{\"redirecturl\": \"" + zaloPayConfig.getReturnUrl() + "\"}");
                put("callback_url", zaloPayConfig.getCallbackUrl());
            }};

            String data = order.get("app_id") + "|" + order.get("app_trans_id") + "|" + order.get("app_user") + "|" + order.get("amount")
                    + "|" + order.get("app_time") + "|" + order.get("embed_data") + "|" + order.get("item");
            order.put("mac", HMACUtil.HMacHexStringEncode(HMACUtil.HMACSHA256, zaloPayConfig.getKey1(), data));

            CloseableHttpClient client = HttpClients.createDefault();
            HttpPost post = new HttpPost(zaloPayConfig.getEndpoint());

            List<NameValuePair> params = new ArrayList<>();
            for (Map.Entry<String, Object> e : order.entrySet()) {
                params.add(new BasicNameValuePair(e.getKey(), e.getValue().toString()));
            }
            post.setEntity(new UrlEncodedFormEntity(params));
            CloseableHttpResponse res = client.execute(post);
            BufferedReader rd = new BufferedReader(new InputStreamReader(res.getEntity().getContent()));
            StringBuilder resultJsonStr = new StringBuilder();
            String line;
            while ((line = rd.readLine()) != null) {
                resultJsonStr.append(line);
            }

            JSONObject jsonResult = new JSONObject(resultJsonStr.toString());
            log.info("ZaloPay response: {}", jsonResult);

            if (jsonResult.getInt("return_code") == 1) {
                return CreatePaymentResponse.builder()
                        .code("200")
                        .message("Successfully created ZaloPay payment URL")
                        .paymentUrl(jsonResult.getString("order_url"))
                        .build();
            } else {
                throw new AppException(ErrorCode.PAYMENT_FAILED);
            }
        } catch (Exception e) {
            log.error("Failed to create ZaloPay transaction", e);
            throw new AppException(ErrorCode.PAYMENT_FAILED);
        }
    }

    @Override
    public String getRedirectUrlByTransactionId(String transactionId) {
        return paymentRepository.findByTransactionId(transactionId)
                .map(Payment::getRedirectUrl)
                .orElse(null);
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
            String appTransId = dataObj.getString("app_trans_id");

            Payment payment = paymentRepository.findByTransactionId(appTransId)
                    .orElseThrow(() -> new AppException(ErrorCode.PAYMENT_NOT_FOUND));

            if (payment.getStatus() != PaymentStatus.PENDING) {
                return true; 
            }

            payment.setStatus(PaymentStatus.COMPLETED);
            paymentMessageProducer.sendPaymentCompletedEvent(PaymentCompletedEvent.builder().orderId(payment.getOrderId()).paymentId(payment.getPaymentID()).paymentMethod(payment.getPaymentMethod()).status(PaymentStatus.COMPLETED.name()).build());
            paymentRepository.save(payment);
            return true;

        } catch (Exception ex) {
            log.error("Error processing ZaloPay callback", ex);
            return false;
        }
    }
}
