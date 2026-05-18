package com.notfound.paymentservice.service;

import com.notfound.paymentservice.model.dto.request.PaymentRequest;
import com.notfound.paymentservice.model.dto.request.ZaloPayCallbackRequest;
import com.notfound.paymentservice.model.dto.response.CreatePaymentResponse;

public interface ZaloPayService {
    CreatePaymentResponse createOrderTransaction(PaymentRequest request);
    boolean handleCallback(ZaloPayCallbackRequest cbData);
    String getRedirectUrlByTransactionId(String transactionId);
    void markPaymentCompleted(String appTransId);
    void markPaymentFailed(String appTransId);
}
