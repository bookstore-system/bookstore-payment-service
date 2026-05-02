package com.hamtech.bookstorepaymentservice.util;

import com.hamtech.bookstorepaymentservice.config.MoMoConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

@Slf4j
@Component
@RequiredArgsConstructor
public class MoMoUtil {

    private final MoMoConfig moMoConfig;

    /**
     * Generate HMAC SHA256 signature for MoMo
     */
    public String generateSignature(String rawData) {
        try {
            Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
            SecretKeySpec secret_key = new SecretKeySpec(
                    moMoConfig.getSecretKey().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            );
            sha256_HMAC.init(secret_key);
            byte[] hash = sha256_HMAC.doFinal(rawData.getBytes(StandardCharsets.UTF_8));

            StringBuilder result = new StringBuilder();
            for (byte b : hash) {
                result.append(String.format("%02x", b));
            }
            return result.toString();

        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Error generating MoMo signature: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to generate MoMo signature", e);
        }
    }

    /**
     * Verify MoMo callback signature
     */
    public boolean verifySignature(String rawData, String signature) {
        String calculatedSignature = generateSignature(rawData);
        return calculatedSignature.equals(signature);
    }
}

