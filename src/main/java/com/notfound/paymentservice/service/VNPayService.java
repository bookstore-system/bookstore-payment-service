package com.notfound.paymentservice.service;

import com.notfound.paymentservice.model.dto.request.PaymentRequest;
import com.notfound.paymentservice.model.dto.request.VNPayCallbackRequest;
import com.notfound.paymentservice.model.dto.response.CreatePaymentResponse;
import com.notfound.paymentservice.model.dto.response.PaymentResponse;
import jakarta.servlet.http.HttpServletRequest;

public interface VNPayService {
    CreatePaymentResponse createVNPayPaymentUrl(PaymentRequest request, HttpServletRequest httpServletRequest);
    PaymentResponse handleVNPayReturn(VNPayCallbackRequest vnpParamsRequest);
    String getRedirectUrlByTransactionId(String transactionId);
}
