package com.hamtech.bookstorepaymentservice.controller;

import com.hamtech.bookstorepaymentservice.model.dto.request.MoMoCallbackRequest;
import com.hamtech.bookstorepaymentservice.model.dto.request.PaymentRequest;
import com.hamtech.bookstorepaymentservice.model.dto.request.VNPayCallbackRequest;
import com.hamtech.bookstorepaymentservice.model.dto.request.ZaloPayCallbackRequest;
import com.hamtech.bookstorepaymentservice.model.dto.response.ApiResponse;
import com.hamtech.bookstorepaymentservice.model.dto.response.CreatePaymentResponse;
import com.hamtech.bookstorepaymentservice.model.dto.response.PaymentResponse;
import com.hamtech.bookstorepaymentservice.model.dto.response.ZaloPayCallBackResponseDTO;
import com.hamtech.bookstorepaymentservice.service.impl.MoMoServiceImpl;
import com.hamtech.bookstorepaymentservice.service.impl.VNPayServiceImpl;
import com.hamtech.bookstorepaymentservice.service.impl.ZaloPayServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@Slf4j
@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final VNPayServiceImpl vnPayService;
    private final ZaloPayServiceImpl zaloPayService;
    private final MoMoServiceImpl moMoService;

    @Value("${frontend.url}")
    private String frontendUrl;

    // ================== VNPAY ==================

    @PostMapping("/vnpay/create")
    public ApiResponse<CreatePaymentResponse> createVNPayPayment(
            @RequestBody PaymentRequest request,
            HttpServletRequest httpServletRequest
    ) {
        CreatePaymentResponse vnPayPaymentUrl = vnPayService.createVNPayPaymentUrl(request, httpServletRequest);
        return ApiResponse.<CreatePaymentResponse>builder()
                .code(200)
                .result(vnPayPaymentUrl)
                .message("Successfully created VNPay payment URL")
                .build();
    }

    @GetMapping("/vnpay/callback")
    public void handleVNPayReturn(
            VNPayCallbackRequest vnpParams,
            HttpServletResponse response
    ) throws IOException {
        PaymentResponse paymentResponse = vnPayService.handleVNPayReturn(vnpParams);
        String redirectUrl = vnPayService.getRedirectUrlByTransactionId(vnpParams.getVnp_TxnRef());

        if (redirectUrl == null || redirectUrl.isEmpty()) {
            redirectUrl = frontendUrl + "/payment-result";
        }

        String finalRedirectUrl = redirectUrl + "?status=" + paymentResponse.getStatus().name()
                + "&orderId=" + paymentResponse.getOrderId()
                + "&amount=" + paymentResponse.getAmount();

        response.sendRedirect(finalRedirectUrl);
    }

    // ================== ZALOPAY ==================

    @PostMapping("/zalopay/create")
    public ApiResponse<CreatePaymentResponse> createZaloPayment(
            @RequestBody PaymentRequest request
    ) {
        CreatePaymentResponse response = zaloPayService.createOrderTransaction(request);
        return ApiResponse.<CreatePaymentResponse>builder()
                .code(200)
                .message("Payment order created successfully")
                .result(response)
                .build();
    }

    @PostMapping("/zalopay/callback")
    public ZaloPayCallBackResponseDTO callbackZaloPay(@RequestBody ZaloPayCallbackRequest body) {
        boolean success = zaloPayService.handleCallback(body);
        if (success) {
            return ZaloPayCallBackResponseDTO.builder()
                    .returnCode(1)
                    .returnMessage("success")
                    .build();
        } else {
            return ZaloPayCallBackResponseDTO.builder()
                    .returnCode(0)
                    .returnMessage("fail")
                    .build();
        }
    }

    @GetMapping("/zalopay/return")
    public void handleZaloPayReturn(
            @RequestParam(required = false) String apptransid,
            @RequestParam(required = false) Integer status,
            HttpServletResponse response
    ) throws IOException {
        String baseRedirectUrl = frontendUrl + "/payment-result";
        String finalRedirectUrl = baseRedirectUrl + "?apptransid=" + apptransid + "&status=" + (status != null && status == 1 ? "COMPLETED" : "FAILED");
        response.sendRedirect(finalRedirectUrl);
    }

    // ================== MOMO ==================

    @PostMapping("/momo/create")
    public ApiResponse<CreatePaymentResponse> createMoMoPayment(
            @RequestBody PaymentRequest request
    ) {
        CreatePaymentResponse response = moMoService.createMoMoPayment(request);
        return ApiResponse.<CreatePaymentResponse>builder()
                .code(200)
                .message("Payment order created successfully")
                .result(response)
                .build();
    }

    @PostMapping("/momo/callback")
    public ApiResponse<PaymentResponse> handleMoMoCallback(
            @RequestBody MoMoCallbackRequest callback
    ) {
        PaymentResponse response = moMoService.handleMoMoCallback(callback);
        return ApiResponse.<PaymentResponse>builder()
                .code(200)
                .message("Callback processed successfully")
                .result(response)
                .build();
    }

    @GetMapping("/momo/return")
    public void handleMoMoReturn(
            @RequestParam(required = false) String orderId,
            @RequestParam(required = false) Integer resultCode,
            HttpServletResponse response
    ) throws IOException {
        String baseRedirectUrl = frontendUrl + "/payment-result";
        String status = (resultCode != null && resultCode == 0) ? "COMPLETED" : "FAILED";
        String finalRedirectUrl = baseRedirectUrl + "?orderId=" + orderId + "&status=" + status;
        response.sendRedirect(finalRedirectUrl);
    }
}
