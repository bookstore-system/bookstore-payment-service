package com.notfound.paymentservice.service;

import com.notfound.paymentservice.config.ZaloPayConfig;
import com.notfound.paymentservice.exception.AppException;
import com.notfound.paymentservice.exception.ErrorCode;
import com.notfound.paymentservice.messaging.PaymentCompletedEvent;
import com.notfound.paymentservice.messaging.PaymentMessageProducer;
import com.notfound.paymentservice.model.dto.request.ZaloPayCallbackRequest;
import com.notfound.paymentservice.model.entity.Payment;
import com.notfound.paymentservice.model.enums.PaymentMethod;
import com.notfound.paymentservice.model.enums.PaymentStatus;
import com.notfound.paymentservice.repository.PaymentRepository;
import com.notfound.paymentservice.service.impl.ZaloPayServiceImpl;
import com.notfound.paymentservice.util.HMACUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ZaloPayServiceImplTest {

    @Mock
    private ZaloPayConfig zaloPayConfig;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentMessageProducer paymentMessageProducer;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private ZaloPayServiceImpl zaloPayService;

    private UUID orderId;
    private static final String TRANSACTION_ID = "250515_1747300000000";
    private static final String DATA_STR = "{\"app_trans_id\":\"" + TRANSACTION_ID + "\"}";
    private static final String VALID_MAC = "computed-valid-mac";
    private static final String KEY2 = "test-key2";

    @BeforeEach
    void setUp() {
        orderId = UUID.randomUUID();
        // getKey2() dùng trong handleCallback nhưng không dùng trong getRedirectUrl
        lenient().when(zaloPayConfig.getKey2()).thenReturn(KEY2);
    }

    @Test
    void handleCallback_macMismatch_returnsFalse() {
        ZaloPayCallbackRequest cbData = new ZaloPayCallbackRequest(DATA_STR, "wrong-mac");

        try (MockedStatic<HMACUtil> hmacStatic = Mockito.mockStatic(HMACUtil.class)) {
            hmacStatic.when(() -> HMACUtil.HMacHexStringEncode(
                    eq(HMACUtil.HMACSHA256), eq(KEY2), eq(DATA_STR)))
                    .thenReturn(VALID_MAC);

            boolean result = zaloPayService.handleCallback(cbData);

            assertThat(result).isFalse();
        }
    }

    @Test
    void handleCallback_paymentNotFound_returnsFalse() {
        ZaloPayCallbackRequest cbData = new ZaloPayCallbackRequest(DATA_STR, VALID_MAC);

        try (MockedStatic<HMACUtil> hmacStatic = Mockito.mockStatic(HMACUtil.class)) {
            hmacStatic.when(() -> HMACUtil.HMacHexStringEncode(
                    eq(HMACUtil.HMACSHA256), eq(KEY2), eq(DATA_STR)))
                    .thenReturn(VALID_MAC);
            when(paymentRepository.findByTransactionId(TRANSACTION_ID)).thenReturn(Optional.empty());

            boolean result = zaloPayService.handleCallback(cbData);

            assertThat(result).isFalse();
        }
    }

    @Test
    void handleCallback_alreadyCompleted_returnsTrueNoEventSent() {
        Payment payment = buildPayment(PaymentStatus.COMPLETED);
        ZaloPayCallbackRequest cbData = new ZaloPayCallbackRequest(DATA_STR, VALID_MAC);

        try (MockedStatic<HMACUtil> hmacStatic = Mockito.mockStatic(HMACUtil.class)) {
            hmacStatic.when(() -> HMACUtil.HMacHexStringEncode(
                    eq(HMACUtil.HMACSHA256), eq(KEY2), eq(DATA_STR)))
                    .thenReturn(VALID_MAC);
            when(paymentRepository.findByTransactionId(TRANSACTION_ID)).thenReturn(Optional.of(payment));

            boolean result = zaloPayService.handleCallback(cbData);

            assertThat(result).isTrue();
            verifyNoInteractions(paymentMessageProducer);
        }
    }

    @Test
    void handleCallback_validMacAndPendingPayment_setsCompletedAndPublishesEvent() {
        Payment payment = buildPayment(PaymentStatus.PENDING);
        ZaloPayCallbackRequest cbData = new ZaloPayCallbackRequest(DATA_STR, VALID_MAC);

        try (MockedStatic<HMACUtil> hmacStatic = Mockito.mockStatic(HMACUtil.class)) {
            hmacStatic.when(() -> HMACUtil.HMacHexStringEncode(
                    eq(HMACUtil.HMACSHA256), eq(KEY2), eq(DATA_STR)))
                    .thenReturn(VALID_MAC);
            when(paymentRepository.findByTransactionId(TRANSACTION_ID)).thenReturn(Optional.of(payment));
            when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            boolean result = zaloPayService.handleCallback(cbData);

            assertThat(result).isTrue();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
            verify(paymentMessageProducer).sendPaymentCompletedEvent(any(PaymentCompletedEvent.class));
        }
    }

    @Test
    void getRedirectUrlByTransactionId_paymentFound_returnsUrl() {
        Payment payment = buildPayment(PaymentStatus.PENDING);
        payment.setRedirectUrl("http://frontend/result");
        when(paymentRepository.findByTransactionId(TRANSACTION_ID)).thenReturn(Optional.of(payment));

        String result = zaloPayService.getRedirectUrlByTransactionId(TRANSACTION_ID);

        assertThat(result).isEqualTo("http://frontend/result");
    }

    @Test
    void getRedirectUrlByTransactionId_paymentNotFound_returnsNull() {
        when(paymentRepository.findByTransactionId("NONE")).thenReturn(Optional.empty());

        String result = zaloPayService.getRedirectUrlByTransactionId("NONE");

        assertThat(result).isNull();
    }

    private Payment buildPayment(PaymentStatus status) {
        return Payment.builder()
                .orderId(orderId)
                .amount(200000L)
                .paymentMethod(PaymentMethod.ZALOPAY.name())
                .status(status)
                .transactionId(TRANSACTION_ID)
                .redirectUrl("http://frontend/result")
                .date(LocalDateTime.now())
                .build();
    }
}
