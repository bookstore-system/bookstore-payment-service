package com.notfound.paymentservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
@Getter
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ZaloPayConfig {

    private final ObjectMapper objectMapper;

    @Value("${payment.zaloPay.appId}")
    String appId;
    @Value("${payment.zaloPay.key1}")
    String key1;
    @Value("${payment.zaloPay.key2}")
    String key2 ;
    @Value("${payment.zaloPay.zaloPayUrl}")
    String endpoint;
    @Value("${payment.zaloPay.returnUrl}")
    String returnUrl;
    @Value("${payment.zaloPay.redirectUrl}")
    String redirectUrl;
    @Value("${payment.zaloPay.callbackUrl}")
    String callbackUrl;


}
