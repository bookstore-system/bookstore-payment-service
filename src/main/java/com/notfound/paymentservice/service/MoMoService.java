package com.notfound.paymentservice.service;

import com.notfound.paymentservice.model.dto.request.MoMoCallbackRequest;
import com.notfound.paymentservice.model.dto.request.PaymentRequest;
import com.notfound.paymentservice.model.dto.response.CreatePaymentResponse;
import com.notfound.paymentservice.model.dto.response.PaymentResponse;

public interface MoMoService {
    CreatePaymentResponse createMoMoPayment(PaymentRequest request);
    PaymentResponse handleMoMoCallback(MoMoCallbackRequest callbackRequest);
}
