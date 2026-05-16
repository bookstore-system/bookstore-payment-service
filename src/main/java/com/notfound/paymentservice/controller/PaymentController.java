package com.notfound.paymentservice.controller;

import com.notfound.paymentservice.model.dto.request.MoMoCallbackRequest;
import com.notfound.paymentservice.model.dto.request.PaymentRequest;
import com.notfound.paymentservice.model.dto.request.VNPayCallbackRequest;
import com.notfound.paymentservice.model.dto.request.ZaloPayCallbackRequest;
import com.notfound.paymentservice.model.dto.response.ApiResponse;
import com.notfound.paymentservice.model.dto.response.CreatePaymentResponse;
import com.notfound.paymentservice.model.dto.response.PaymentResponse;
import com.notfound.paymentservice.model.dto.response.ZaloPayCallBackResponseDTO;
import com.notfound.paymentservice.service.MoMoService;
import com.notfound.paymentservice.service.VNPayService;
import com.notfound.paymentservice.service.ZaloPayService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

@Slf4j
@RestController
@RequestMapping("/api/v1/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final VNPayService vnPayService;
    private final ZaloPayService zaloPayService;
    private final MoMoService moMoService;

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
            redirectUrl = frontendUrl;
        }

        String finalRedirectUrl = UriComponentsBuilder.fromUriString(redirectUrl)
                .queryParam("resultCode", vnpParams.getVnp_ResponseCode())
                .queryParam("message", vnpParams.isSuccess() ? "Thanh toán thành công" : "Thanh toán thất bại")
                .queryParam("orderId", paymentResponse.getOrderId())
                .queryParam("paymentId", paymentResponse.getPaymentId())
                .queryParam("status", paymentResponse.getStatus().name())
                .build()
                .encode()
                .toUriString();

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
        int resultCode = (status != null && status == 1) ? 0 : 1;
        String message = (resultCode == 0) ? "Thanh toán thành công" : "Thanh toán thất bại";
        String paymentStatus = (resultCode == 0) ? "COMPLETED" : "FAILED";

        if (resultCode != 0) {
            try {
                zaloPayService.markPaymentFailed(apptransid);
            } catch (Exception e) {
                log.warn("markPaymentFailed ZaloPay apptransid={} fail: {}", apptransid, e.getMessage());
            }
        }

        String redirectUrl = frontendUrl;
        if (apptransid != null && !apptransid.isEmpty()) {
            try {
                String dbRedirect = zaloPayService.getRedirectUrlByTransactionId(apptransid);
                if (dbRedirect != null && !dbRedirect.isEmpty()) {
                    redirectUrl = dbRedirect;
                }
            } catch (Exception ignored) {
            }
        }

        String finalRedirectUrl = UriComponentsBuilder.fromUriString(redirectUrl)
                .queryParam("resultCode", resultCode)
                .queryParam("message", message)
                .queryParam("orderId", "")
                .queryParam("paymentId", "")
                .queryParam("status", paymentStatus)
                .build()
                .encode()
                .toUriString();

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
            @RequestParam(required = false) String partnerCode,
            @RequestParam(required = false) String orderId,
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) Long amount,
            @RequestParam(required = false) String orderInfo,
            @RequestParam(required = false) String orderType,
            @RequestParam(required = false) Long transId,
            @RequestParam(required = false) Integer resultCode,
            @RequestParam(required = false) String message,
            @RequestParam(required = false) String payType,
            @RequestParam(required = false) Long responseTime,
            @RequestParam(required = false) String extraData,
            @RequestParam(required = false) String signature,
            HttpServletResponse response
    ) throws IOException {
        MoMoCallbackRequest callback = new MoMoCallbackRequest();
        callback.setPartnerCode(partnerCode);
        callback.setOrderId(orderId);
        callback.setRequestId(requestId);
        callback.setAmount(amount);
        callback.setOrderInfo(orderInfo);
        callback.setOrderType(orderType);
        callback.setTransId(transId);
        callback.setResultCode(resultCode);
        callback.setMessage(message);
        callback.setPayType(payType);
        callback.setResponseTime(responseTime);
        callback.setExtraData(extraData);
        callback.setSignature(signature);

        PaymentResponse paymentResponse = moMoService.handleMoMoCallback(callback);

        String redirectUrl = moMoService.getRedirectUrlByTransactionId(orderId);
        if (redirectUrl == null || redirectUrl.isEmpty()) {
            if (extraData != null && !extraData.isEmpty()) {
                try {
                    byte[] decodedBytes = java.util.Base64.getDecoder().decode(extraData);
                    redirectUrl = new String(decodedBytes);
                } catch (Exception ignored) {
                    redirectUrl = frontendUrl;
                }
            } else {
                redirectUrl = frontendUrl;
            }
        }

        String finalRedirectUrl = UriComponentsBuilder.fromUriString(redirectUrl)
                .queryParam("resultCode", resultCode)
                .queryParam("message", message != null ? message : "")
                .queryParam("orderId", paymentResponse.getOrderId())
                .queryParam("paymentId", paymentResponse.getPaymentId())
                .queryParam("status", paymentResponse.getStatus().name())
                .build()
                .encode()
                .toUriString();

        response.sendRedirect(finalRedirectUrl);
    }
}
