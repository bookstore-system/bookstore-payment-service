package com.hamtech.bookstorepaymentservice.util;

import com.hamtech.bookstorepaymentservice.config.VNPayConfig;
import com.hamtech.bookstorepaymentservice.exception.AppException;
import com.hamtech.bookstorepaymentservice.exception.ErrorCode;
import com.hamtech.bookstorepaymentservice.model.dto.request.VNPayCallbackRequest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class VNPayUtil {

    private final VNPayConfig vnPayConfig;
    private static final String VNP_SECURE_HASH_KEY = "vnp_SecureHash";

    public String generatePaymentUrl(String transactionId, Long amount, HttpServletRequest request) {
        String clientIp = getClientIpAddress(request);

        // 1. Get VNPay params
        Map<String, String> vnpParams = vnPayConfig.getVNPayConfig(transactionId, amount, clientIp);

        // 2. Sort keys
        List<String> fieldNames = new ArrayList<>(vnpParams.keySet());
        Collections.sort(fieldNames);

        // 3. Build HASH DATA (WITH URL ENCODING - theo code mẫu VNPay)
        StringBuilder hashData = new StringBuilder();

        // 4. Build QUERY STRING (WITH URL ENCODING)
        StringBuilder query = new StringBuilder();

        Iterator<String> itr = fieldNames.iterator();

        while (itr.hasNext()) {
            String fieldName = itr.next();
            String fieldValue = vnpParams.get(fieldName);

            if (fieldValue != null && !fieldValue.isEmpty()) {
                try {
                    // Build hash data WITH URL ENCODING (theo code mẫu VNPay)
                    hashData.append(fieldName);
                    hashData.append('=');
                    hashData.append(URLEncoder.encode(fieldValue, StandardCharsets.US_ASCII));

                    // Build query WITH URL ENCODING
                    query.append(URLEncoder.encode(fieldName, StandardCharsets.US_ASCII));
                    query.append('=');
                    query.append(URLEncoder.encode(fieldValue, StandardCharsets.US_ASCII));

                    if (itr.hasNext()) {
                        query.append('&');
                        hashData.append('&');
                    }
                } catch (Exception e) {
                    log.error("Error encoding:",  e);
                }
            }
        }

        // 5. Generate hash
        String hashDataStr = hashData.toString();


        String secureHash = hmacSHA512(vnPayConfig.getSecretKey(), hashDataStr);

        // 6. Build final URL
        String queryUrl = query.toString();
        queryUrl += "&" + VNP_SECURE_HASH_KEY + "=" + secureHash;

        return vnPayConfig.getVnp_PayUrl() + "?" + queryUrl;
    }

    /**
     * Get client IP address
     */
    private String getClientIpAddress(HttpServletRequest request) {
        String ip = request.getHeader("X-FORWARDED-FOR");
        if (ip == null || ip.isBlank()) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }

        // Handle IPv6 localhost
        if ("0:0:0:0:0:0:0:1".equals(ip)) {
            ip = "127.0.0.1";
        }

        return ip;
    }

    /**
     * Generate HMAC SHA512
     */
    private String hmacSHA512(String key, String data) {
        try {
            if (key == null || data == null) {
                throw new IllegalArgumentException("Key and data must not be null");
            }

            Mac hmac512 = Mac.getInstance("HmacSHA512");
            SecretKeySpec secretKey = new SecretKeySpec(
                    key.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA512"
            );
            hmac512.init(secretKey);
            byte[] hashBytes = hmac512.doFinal(data.getBytes(StandardCharsets.UTF_8));

            // Convert to hex
            StringBuilder result = new StringBuilder();
            for (byte b : hashBytes) {
                result.append(String.format("%02x", b));
            }

            return result.toString();

        } catch (Exception ex) {
            throw new AppException(ErrorCode.ERROR_CREATE_HMACSHA512);
        }
    }

    /**
     * Verify VNPay response signature
     */
    public boolean verifyPaymentResponse(Map<String, String> params) {
        String vnpSecureHash = params.get(VNP_SECURE_HASH_KEY);

        if (vnpSecureHash == null) {
            return false;
        }

        // Remove hash fields
        Map<String, String> paramsToHash = new HashMap<>(params);
        paramsToHash.remove(VNP_SECURE_HASH_KEY);
        paramsToHash.remove("vnp_SecureHashType");

        // Build hash data
        List<String> fieldNames = new ArrayList<>(paramsToHash.keySet());
        Collections.sort(fieldNames);

        StringBuilder hashData = new StringBuilder();
        Iterator<String> itr = fieldNames.iterator();

        while (itr.hasNext()) {
            String fieldName = itr.next();
            String fieldValue = paramsToHash.get(fieldName);

            if (fieldValue != null && !fieldValue.isEmpty()) {
                try {
                    hashData.append(fieldName);
                    hashData.append('=');
                    hashData.append(URLEncoder.encode(fieldValue, StandardCharsets.US_ASCII));

                    if (itr.hasNext()) {
                        hashData.append('&');
                    }
                } catch (Exception e) {
                }
            }
        }
        String calculatedHash = hmacSHA512(vnPayConfig.getSecretKey(), hashData.toString());
        return vnpSecureHash.equals(calculatedHash);
    }

    /**
     * Verify VNPay callback signature
     * @param vnpParams VNPay callback request
     * @return true if signature is valid, false otherwise
     */
    public boolean verifyReturnDataSignature(VNPayCallbackRequest vnpParams) {
        try {
            String vnpSecureHash = vnpParams.getVnp_SecureHash();

            if (vnpSecureHash == null || vnpSecureHash.isEmpty()) {
                return false;
            }

            List<String> hashDataParts = new ArrayList<>();

            if (vnpParams.getVnp_Amount() != null)
                hashDataParts.add("vnp_Amount=" + URLEncoder.encode(vnpParams.getVnp_Amount(), StandardCharsets.US_ASCII));

            if (vnpParams.getVnp_BankCode() != null)
                hashDataParts.add("vnp_BankCode=" + URLEncoder.encode(vnpParams.getVnp_BankCode(), StandardCharsets.US_ASCII));

            if (vnpParams.getVnp_BankTranNo() != null)
                hashDataParts.add("vnp_BankTranNo=" + URLEncoder.encode(vnpParams.getVnp_BankTranNo(), StandardCharsets.US_ASCII));

            if (vnpParams.getVnp_CardType() != null)
                hashDataParts.add("vnp_CardType=" + URLEncoder.encode(vnpParams.getVnp_CardType(), StandardCharsets.US_ASCII));

            if (vnpParams.getVnp_OrderInfo() != null)
                hashDataParts.add("vnp_OrderInfo=" + URLEncoder.encode(vnpParams.getVnp_OrderInfo(), StandardCharsets.US_ASCII));

            if (vnpParams.getVnp_PayDate() != null)
                hashDataParts.add("vnp_PayDate=" + URLEncoder.encode(vnpParams.getVnp_PayDate(), StandardCharsets.US_ASCII));

            if (vnpParams.getVnp_ResponseCode() != null)
                hashDataParts.add("vnp_ResponseCode=" + URLEncoder.encode(vnpParams.getVnp_ResponseCode(), StandardCharsets.US_ASCII));

            if (vnpParams.getVnp_TmnCode() != null)
                hashDataParts.add("vnp_TmnCode=" + URLEncoder.encode(vnpParams.getVnp_TmnCode(), StandardCharsets.US_ASCII));

            if (vnpParams.getVnp_TransactionNo() != null)
                hashDataParts.add("vnp_TransactionNo=" + URLEncoder.encode(vnpParams.getVnp_TransactionNo(), StandardCharsets.US_ASCII));

            if (vnpParams.getVnp_TransactionStatus() != null)
                hashDataParts.add("vnp_TransactionStatus=" + URLEncoder.encode(vnpParams.getVnp_TransactionStatus(), StandardCharsets.US_ASCII));

            if (vnpParams.getVnp_TxnRef() != null)
                hashDataParts.add("vnp_TxnRef=" + URLEncoder.encode(vnpParams.getVnp_TxnRef(), StandardCharsets.US_ASCII));

            Collections.sort(hashDataParts);

            String hashData = String.join("&", hashDataParts);

            String calculatedHash = hmacSHA512(vnPayConfig.getSecretKey(), hashData);

            return calculatedHash.equalsIgnoreCase(vnpSecureHash);

        } catch (Exception e) {
            log.error("Error verifying signature: {}", e.getMessage(), e);
            return false;
        }
    }
}