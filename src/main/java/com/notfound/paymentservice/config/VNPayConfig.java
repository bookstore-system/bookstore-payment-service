package com.notfound.paymentservice.config;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Configuration
@Getter
@FieldDefaults(level = AccessLevel.PRIVATE)
public class VNPayConfig {
    @Value("${payment.vnPay.url}")
    String vnp_PayUrl;
    @Value("${payment.vnPay.returnUrl}")
    String vnp_ReturnUrl;
    @Value("${payment.vnPay.tmnCode}")
    String vnp_TmnCode ;
    @Value("${payment.vnPay.secretKey}")
    String secretKey;
    @Value("${payment.vnPay.version}")
    String vnp_Version;
    @Value("${payment.vnPay.command}")
    String vnp_Command;
    @Value("${payment.vnPay.orderType}")
    String orderType;

    /**
     * Tạo Map chứa các tham số cho thanh toán VNPAY
     */
    public Map<String, String> getVNPayConfig(String transactionId, Long amount, String clientIp) {
        Map<String, String> vnpParamsMap = new HashMap<>();

        //1. CRITICAL: vnp_Amount phải nhân 100 và là số nguyên
        long vnpAmount = (amount * 100);

        vnpParamsMap.put("vnp_Version", vnp_Version);
        vnpParamsMap.put("vnp_Command", vnp_Command);
        vnpParamsMap.put("vnp_TmnCode", vnp_TmnCode);
        vnpParamsMap.put("vnp_Amount", String.valueOf(vnpAmount));
        vnpParamsMap.put("vnp_CurrCode", "VND");
        vnpParamsMap.put("vnp_TxnRef", transactionId);

        //2. CRITICAL: OrderInfo KHÔNG được có dấu tiếng Việt
        String orderInfo = removeVietnameseAccentsAndSpaces(
                "Thanh toan don hang " + transactionId.replace("_", "")
        );
        vnpParamsMap.put("vnp_OrderInfo", orderInfo);

        vnpParamsMap.put("vnp_OrderType", orderType);
        vnpParamsMap.put("vnp_Locale", "vn");
        vnpParamsMap.put("vnp_ReturnUrl", vnp_ReturnUrl);
        vnpParamsMap.put("vnp_IpAddr", clientIp);

        //3. Timezone GMT+7
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
        Instant now = Instant.now();

        String createDate = formatter.format(now);
        String expireDate = formatter.format(now.plusSeconds(15 * 60));

        vnpParamsMap.put("vnp_CreateDate", createDate);
        vnpParamsMap.put("vnp_ExpireDate", expireDate);

        return vnpParamsMap;
    }

    /**
     * Loại bỏ dấu tiếng Việt
     */
    private String removeVietnameseAccentsAndSpaces(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        // Normalize Unicode
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD);

        // Remove diacritics
        String result = normalized.replaceAll("\\p{M}", "");

        // Replace đ and Đ
        result = result.replaceAll("đ", "d").replaceAll("Đ", "D");

        // ✅ CRITICAL: Replace spaces with empty string
        result = result.replaceAll("\\s+", "");

        // Remove special characters, keep only alphanumeric
        result = result.replaceAll("[^a-zA-Z0-9]", "");

        return result;
    }
}
