package com.notfound.paymentservice.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notfound.paymentservice.config.ZaloPayConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ZaloPayUtil {
    private final ZaloPayConfig properties;
    private final ObjectMapper objectMapper;

    public Map<String, Object> createOrder(String appTransId, String appUser, long amount) throws IOException {
        log.info("========================================");
        log.info("    ZaloPay Create Order Request (TPE API)");
        log.info("========================================");

        // Build embed_data
        Map<String, Object> embedData = new HashMap<>();
        embedData.put("redirecturl", properties.getRedirectUrl() != null ? properties.getRedirectUrl() : "");

        String embedDataJson = objectMapper.writeValueAsString(embedData);

        // Build item array (empty for now)
        String itemJson = "[]";

        // Build order parameters
        Map<String, Object> order = new HashMap<>();
        order.put("appid", properties.getAppId());
        order.put("apptransid", appTransId);
        order.put("appuser", appUser);
        order.put("apptime", System.currentTimeMillis());
        order.put("amount", amount);
        order.put("embeddata", embedDataJson);
        order.put("item", itemJson);
        order.put("description", "B&Q Bookstore - Thanh toan don hang #" + appTransId);
        order.put("bankcode", ""); // Empty string for ZaloPay wallet

        // Log request parameters
        log.info("Request Parameters:");
        log.info("  appid           : {}", order.get("appid"));
        log.info("  apptransid      : {}", order.get("apptransid"));
        log.info("  appuser         : {}", order.get("appuser"));
        log.info("  apptime         : {}", order.get("apptime"));
        log.info("  amount          : {}", order.get("amount"));
        log.info("  embeddata       : {}", order.get("embeddata"));
        log.info("  item            : {}", order.get("item"));
        log.info("  description     : {}", order.get("description"));
        log.info("  bankcode        : {}", order.get("bankcode"));

        // ✅ FIX: Generate MAC theo đúng format TPE API
        // Data format: appid|apptransid|appuser|amount|apptime|embeddata|item
        String data = String.format("%s|%s|%s|%s|%s|%s|%s",
                order.get("appid"),
                order.get("apptransid"),
                order.get("appuser"),
                order.get("amount"),
                order.get("apptime"),
                order.get("embeddata"),
                order.get("item")
        );

        log.info("Data to sign      : {}", data);
        log.info("Key1              : {}", properties.getKey1());

        String mac = HMACUtil.HMacHexStringEncode(HMACUtil.HMACSHA256, properties.getKey1(), data);
        order.put("mac", mac);

        log.info("Generated MAC     : {}", mac);
        log.info("ZaloPay URL       : {}", properties.getEndpoint());
        log.info("========================================");

        // Send HTTP request
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(properties.getEndpoint());
            post.setHeader("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");

            List<NameValuePair> params = order.entrySet().stream()
                    .map(e -> new BasicNameValuePair(e.getKey(), e.getValue().toString()))
                    .collect(Collectors.toList());

            post.setEntity(new UrlEncodedFormEntity(params, StandardCharsets.UTF_8));

            log.info("Sending request to ZaloPay TPE...");

            try (CloseableHttpResponse res = client.execute(post)) {
                int statusCode = res.getStatusLine().getStatusCode();

                log.info("========================================");
                log.info("    ZaloPay Create Order Response");
                log.info("========================================");
                log.info("HTTP Status       : {} {}", statusCode, res.getStatusLine().getReasonPhrase());

                String resultJson = new BufferedReader(
                        new InputStreamReader(res.getEntity().getContent(), StandardCharsets.UTF_8)
                ).lines().collect(Collectors.joining("\n"));

                log.info("Raw Response JSON : {}", resultJson);

                @SuppressWarnings("unchecked")
                Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

                // Log parsed response
                log.info("Parsed Response:");
                result.forEach((key, value) -> log.info("  {} = {}", key, value));
                log.info("========================================");

                return result;
            }
        } catch (IOException e) {
            log.error("========================================");
            log.error("    ZaloPay Request Failed");
            log.error("========================================");
            log.error("Error: {}", e.getMessage(), e);
            throw e;
        }
    }

    public Map<String, Object> getOrder(String appTransId) throws IOException {
        log.info("========================================");
        log.info("    ZaloPay Query Order Status");
        log.info("========================================");

        String data = String.format("%s|%s|%s",
                properties.getAppId(),
                appTransId,
                properties.getKey1()
        );

        log.info("Query Parameters:");
        log.info("  app_id         : {}", properties.getAppId());
        log.info("  app_trans_id   : {}", appTransId);
        log.info("  data to sign   : {}", data);

        String mac = HMACUtil.HMacHexStringEncode(HMACUtil.HMACSHA256, properties.getKey1(), data);

        log.info("  mac            : {}", mac);
        log.info("  url            : {}", properties.getEndpoint());

        List<NameValuePair> params = Arrays.asList(
                new BasicNameValuePair("app_id", properties.getAppId()),
                new BasicNameValuePair("app_trans_id", appTransId),
                new BasicNameValuePair("mac", mac)
        );

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(properties.getEndpoint());
            post.setHeader("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            post.setEntity(new UrlEncodedFormEntity(params, StandardCharsets.UTF_8));

            log.info("Querying ZaloPay order status...");

            try (CloseableHttpResponse res = client.execute(post)) {
                int statusCode = res.getStatusLine().getStatusCode();

                log.info("HTTP Status       : {}", statusCode);

                String resultJson = new BufferedReader(
                        new InputStreamReader(res.getEntity().getContent(), StandardCharsets.UTF_8)
                ).lines().collect(Collectors.joining("\n"));

                log.info("Raw Response JSON : {}", resultJson);

                @SuppressWarnings("unchecked")
                Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

                log.info("Parsed Response:");
                log.info("  return_code    : {}", result.get("return_code"));
                log.info("  return_message : {}", result.get("return_message"));
                log.info("  is_processing  : {}", result.get("is_processing"));
                log.info("  amount         : {}", result.get("amount"));
                log.info("========================================");

                return result;
            }
        } catch (IOException e) {
            log.error("Failed to query ZaloPay order: {}", e.getMessage(), e);
            throw e;
        }
    }

    @SuppressWarnings("unused")
    public Map<String, Object> createOrderZaloPay(String appTransId, String appUser, long amount) throws IOException {
        return createOrder(appTransId, appUser, amount);
    }

    @SuppressWarnings("unused")
    public Map<String, Object> getOrderZaloPay(String appTransId) throws IOException {
        return getOrder(appTransId);
    }

    @SuppressWarnings("unused")
    public static String getCurrentTimeString(String format) {
        Calendar cal = new GregorianCalendar(TimeZone.getTimeZone("GMT+7"));
        SimpleDateFormat fmt = new SimpleDateFormat(format);
        fmt.setCalendar(cal);
        return fmt.format(cal.getTimeInMillis());
    }
}