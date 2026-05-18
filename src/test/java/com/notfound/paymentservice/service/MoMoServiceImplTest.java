package com.notfound.paymentservice.service;

import com.notfound.paymentservice.config.MoMoConfig;
import com.notfound.paymentservice.exception.AppException;
import com.notfound.paymentservice.exception.ErrorCode;
import com.notfound.paymentservice.messaging.PaymentCompletedEvent;
import com.notfound.paymentservice.messaging.PaymentMessageProducer;
import com.notfound.paymentservice.model.dto.request.MoMoCallbackRequest;
import com.notfound.paymentservice.model.dto.response.PaymentResponse;
import com.notfound.paymentservice.model.entity.Payment;
import com.notfound.paymentservice.model.enums.PaymentMethod;
import com.notfound.paymentservice.model.enums.PaymentStatus;
import com.notfound.paymentservice.repository.PaymentRepository;
import com.notfound.paymentservice.service.impl.MoMoServiceImpl;
import com.notfound.paymentservice.util.MoMoUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MoMoServiceImplTest {

    @Mock
    private MoMoConfig moMoConfig;

    @Mock
    private MoMoUtil moMoUtil;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentMessageProducer paymentMessageProducer;

    @InjectMocks
    private MoMoServiceImpl moMoService;

    private UUID orderId;

    @BeforeEach
    void setUp() {
        orderId = UUID.randomUUID();
        // getAccessKey() được gọi khi build rawData trong handleMoMoCallback
        lenient().when(moMoConfig.getAccessKey()).thenReturn("test-access-key");
    }

    @Test
    void handleMoMoCallback_invalidSignature_throwsInvalidSignatureException() {
        MoMoCallbackRequest callback = buildCallback(0, "bad-sig");
        when(moMoUtil.verifySignature(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> moMoService.handleMoMoCallback(callback))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_SIGNATURE));
    }

    @Test
    void handleMoMoCallback_paymentNotFound_throwsPaymentNotFoundException() {
        MoMoCallbackRequest callback = buildCallback(0, "valid-sig");
        when(moMoUtil.verifySignature(anyString(), anyString())).thenReturn(true);
        when(paymentRepository.findByTransactionId(callback.getOrderId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> moMoService.handleMoMoCallback(callback))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_NOT_FOUND));
    }

    @Test
    void handleMoMoCallback_alreadyCompleted_returnsCurrent_noEventSent() {
        Payment payment = buildPayment(PaymentStatus.COMPLETED);
        MoMoCallbackRequest callback = buildCallback(0, "valid-sig");
        when(moMoUtil.verifySignature(anyString(), anyString())).thenReturn(true);
        when(paymentRepository.findByTransactionId(callback.getOrderId())).thenReturn(Optional.of(payment));

        PaymentResponse response = moMoService.handleMoMoCallback(callback);

        assertThat(response.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        verifyNoInteractions(paymentMessageProducer);
    }

    @Test
    void handleMoMoCallback_resultCodeZero_setsCompletedAndPublishesEvent() {
        Payment payment = buildPayment(PaymentStatus.PENDING);
        MoMoCallbackRequest callback = buildCallback(0, "valid-sig");
        when(moMoUtil.verifySignature(anyString(), anyString())).thenReturn(true);
        when(paymentRepository.findByTransactionId(callback.getOrderId())).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentResponse response = moMoService.handleMoMoCallback(callback);

        assertThat(response.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        verify(paymentMessageProducer).sendPaymentCompletedEvent(any(PaymentCompletedEvent.class));
    }

    @Test
    void handleMoMoCallback_nonZeroResultCode_setsFailedAndPublishesFailedEvent() {
        Payment payment = buildPayment(PaymentStatus.PENDING);
        MoMoCallbackRequest callback = buildCallback(1006, "valid-sig");
        when(moMoUtil.verifySignature(anyString(), anyString())).thenReturn(true);
        when(paymentRepository.findByTransactionId(callback.getOrderId())).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentResponse response = moMoService.handleMoMoCallback(callback);

        assertThat(response.getStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(paymentMessageProducer).sendPaymentFailedEvent(any(PaymentCompletedEvent.class));
        verify(paymentMessageProducer, org.mockito.Mockito.never()).sendPaymentCompletedEvent(any());
    }

    @Test
    void getRedirectUrlByTransactionId_paymentFound_returnsUrl() {
        Payment payment = buildPayment(PaymentStatus.PENDING);
        payment.setRedirectUrl("http://frontend/result");
        when(paymentRepository.findByTransactionId("MOMO-TXN")).thenReturn(Optional.of(payment));

        String result = moMoService.getRedirectUrlByTransactionId("MOMO-TXN");

        assertThat(result).isEqualTo("http://frontend/result");
    }

    @Test
    void getRedirectUrlByTransactionId_paymentNotFound_returnsNull() {
        when(paymentRepository.findByTransactionId("NONE")).thenReturn(Optional.empty());

        String result = moMoService.getRedirectUrlByTransactionId("NONE");

        assertThat(result).isNull();
    }

    private Payment buildPayment(PaymentStatus status) {
        return Payment.builder()
                .orderId(orderId)
                .amount(50000L)
                .paymentMethod(PaymentMethod.MOMO.name())
                .status(status)
                .transactionId(orderId + "-1234567890")
                .redirectUrl("http://frontend/result")
                .date(LocalDateTime.now())
                .build();
    }

    private MoMoCallbackRequest buildCallback(int resultCode, String signature) {
        return MoMoCallbackRequest.builder()
                .partnerCode("MOMO")
                .orderId(orderId + "-1234567890")
                .requestId("REQ-001")
                .amount(50000L)
                .orderInfo("Thanh toan MoMo")
                .orderType("momo_wallet")
                .transId(99999L)
                .resultCode(resultCode)
                .message("Thanh cong")
                .payType("qr")
                .responseTime(System.currentTimeMillis())
                .extraData("")
                .signature(signature)
                .build();
    }
}
