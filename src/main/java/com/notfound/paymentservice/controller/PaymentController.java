package com.notfound.paymentservice.controller;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

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
import com.notfound.paymentservice.util.HMACUtil;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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

    @Value("${payment.zaloPay.key2}")
    private String zaloPayKey2;

    // ================== VNPAY ==================

    @PostMapping("/vnpay/create")
    public ApiResponse<CreatePaymentResponse> createVNPayPayment(
            @RequestBody PaymentRequest request,
            HttpServletRequest httpServletRequest) {
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
            HttpServletResponse response) throws IOException {
        PaymentResponse paymentResponse = vnPayService.handleVNPayReturn(vnpParams);
        String redirectUrl = vnPayService.getRedirectUrlByTransactionId(vnpParams.getVnp_TxnRef());

        if (redirectUrl == null || redirectUrl.isEmpty()) {
            redirectUrl = frontendUrl;
        }

        String finalRedirectUrl = redirectUrl
                + "?resultCode=" + vnpParams.getVnp_ResponseCode()
                + "&message=" + URLEncoder.encode(
                        vnpParams.isSuccess() ? "Thanh toán thành công" : "Thanh toán thất bại",
                        StandardCharsets.UTF_8)
                + "&orderId=" + paymentResponse.getOrderId()
                + "&paymentId=" + paymentResponse.getPaymentId()
                + "&status=" + paymentResponse.getStatus().name();

        response.sendRedirect(finalRedirectUrl);
    }

    // ================== ZALOPAY ==================

    @PostMapping("/zalopay/create")
    public ApiResponse<CreatePaymentResponse> createZaloPayment(
            @RequestBody PaymentRequest request) {
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
            @RequestParam Map<String, String> params,
            HttpServletResponse response) throws IOException {
        String apptransid = params.get("apptransid");
        Integer status = parseInteger(params.get("status"));
        if (!isValidZaloPayRedirect(params)) {
            log.warn("Invalid ZaloPay redirect checksum: apptransid={}", apptransid);
            redirectZaloPayResult(response, frontendUrl, 1, "Thanh toán ZaloPay không hợp lệ", "FAILED");
            return;
        }

        int resultCode = (status != null && status == 1) ? 0 : 1;
        String message = (resultCode == 0) ? "Thanh toán thành công" : "Thanh toán thất bại";
        String paymentStatus = (resultCode == 0) ? "COMPLETED" : "FAILED";

        if (resultCode == 0) {
            try {
                zaloPayService.markPaymentCompleted(apptransid);
            } catch (Exception e) {
                log.warn("markPaymentCompleted ZaloPay apptransid={} fail: {}", apptransid, e.getMessage());
            }
        } else {
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

        redirectZaloPayResult(response, redirectUrl, resultCode, message, paymentStatus);
    }

    private boolean isValidZaloPayRedirect(Map<String, String> params) {
        String checksum = params.get("checksum");
        if (checksum == null || checksum.isBlank()) {
            return true;
        }

        String checksumData = params.getOrDefault("appid", "") + "|"
                + params.getOrDefault("apptransid", "") + "|"
                + params.getOrDefault("pmcid", "") + "|"
                + params.getOrDefault("bankcode", "") + "|"
                + params.getOrDefault("amount", "") + "|"
                + params.getOrDefault("discountamount", "") + "|"
                + params.getOrDefault("status", "");
        String expectedChecksum = HMACUtil.HMacHexStringEncode(
                HMACUtil.HMACSHA256,
                zaloPayKey2,
                checksumData);
        return checksum.equalsIgnoreCase(expectedChecksum);
    }

    private Integer parseInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void redirectZaloPayResult(
            HttpServletResponse response,
            String redirectUrl,
            int resultCode,
            String message,
            String paymentStatus) throws IOException {
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
            @RequestBody PaymentRequest request) {
        CreatePaymentResponse response = moMoService.createMoMoPayment(request);
        return ApiResponse.<CreatePaymentResponse>builder()
                .code(200)
                .message("Payment order created successfully")
                .result(response)
                .build();
    }

    @PostMapping("/momo/callback")
    public ApiResponse<PaymentResponse> handleMoMoCallback(
            @RequestBody MoMoCallbackRequest callback) {
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
            HttpServletResponse response) throws IOException {
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

        String finalRedirectUrl = redirectUrl
                + "?resultCode=" + resultCode
                + "&message=" + (message != null ? URLEncoder.encode(message, StandardCharsets.UTF_8) : "")
                + "&orderId=" + paymentResponse.getOrderId()
                + "&paymentId=" + paymentResponse.getPaymentId()
                + "&status=" + paymentResponse.getStatus().name();

        response.sendRedirect(finalRedirectUrl);
    }
}
