package com.notfound.paymentservice.service;

import com.notfound.paymentservice.config.VNPayConfig;
import com.notfound.paymentservice.exception.AppException;
import com.notfound.paymentservice.exception.ErrorCode;
import com.notfound.paymentservice.messaging.PaymentCompletedEvent;
import com.notfound.paymentservice.messaging.PaymentMessageProducer;
import com.notfound.paymentservice.model.dto.request.PaymentRequest;
import com.notfound.paymentservice.model.dto.request.VNPayCallbackRequest;
import com.notfound.paymentservice.model.dto.response.CreatePaymentResponse;
import com.notfound.paymentservice.model.dto.response.PaymentResponse;
import com.notfound.paymentservice.model.entity.Payment;
import com.notfound.paymentservice.model.enums.PaymentMethod;
import com.notfound.paymentservice.model.enums.PaymentStatus;
import com.notfound.paymentservice.repository.PaymentRepository;
import com.notfound.paymentservice.service.impl.VNPayServiceImpl;
import com.notfound.paymentservice.util.VNPayUtil;
import jakarta.servlet.http.HttpServletRequest;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VNPayServiceImplTest {

    @Mock
    private VNPayConfig vnPayConfig;

    @Mock
    private VNPayUtil vnPayUtil;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentMessageProducer paymentMessageProducer;

    @InjectMocks
    private VNPayServiceImpl vnPayService;

    private UUID orderId;

    @BeforeEach
    void setUp() {
        orderId = UUID.randomUUID();
    }

    @Test
    void createVNPayPaymentUrl_happyPath_savesPaymentAndReturnsUrl() {
        String expectedUrl = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?vnp_Amount=10000000";
        when(vnPayUtil.generatePaymentUrl(anyString(), eq(100000L), any(HttpServletRequest.class)))
                .thenReturn(expectedUrl);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentRequest request = PaymentRequest.builder()
                .orderId(orderId)
                .amount(100000L)
                .redirectUrl("http://localhost:3000/payment/result")
                .build();
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);

        CreatePaymentResponse response = vnPayService.createVNPayPaymentUrl(request, httpRequest);

        assertThat(response.getCode()).isEqualTo("200");
        assertThat(response.getPaymentUrl()).isEqualTo(expectedUrl);
        verify(paymentRepository).save(argThat((Payment p) ->
                p.getOrderId().equals(orderId)
                        && p.getAmount().equals(100000L)
                        && p.getStatus() == PaymentStatus.PENDING
                        && PaymentMethod.VNPAY.name().equals(p.getPaymentMethod())));
    }

    @Test
    void handleVNPayReturn_invalidSignature_throwsInvalidSignatureException() {
        VNPayCallbackRequest request = VNPayCallbackRequest.builder()
                .vnp_TxnRef("TXN-001")
                .vnp_SecureHash("bad-hash")
                .build();
        when(vnPayUtil.verifyReturnDataSignature(request)).thenReturn(false);

        assertThatThrownBy(() -> vnPayService.handleVNPayReturn(request))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_SIGNATURE));
    }

    @Test
    void handleVNPayReturn_paymentNotFound_throwsPaymentNotFoundException() {
        VNPayCallbackRequest request = VNPayCallbackRequest.builder()
                .vnp_TxnRef("TXN-MISSING")
                .vnp_SecureHash("hash")
                .build();
        when(vnPayUtil.verifyReturnDataSignature(request)).thenReturn(true);
        when(paymentRepository.findByTransactionId("TXN-MISSING")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> vnPayService.handleVNPayReturn(request))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_NOT_FOUND));
    }

    @Test
    void handleVNPayReturn_alreadyCompleted_returnsCurrent_noEventSent() {
        Payment payment = buildPayment(PaymentStatus.COMPLETED);
        VNPayCallbackRequest request = buildSuccessCallback();
        when(vnPayUtil.verifyReturnDataSignature(request)).thenReturn(true);
        when(paymentRepository.findByTransactionId("TXN-001")).thenReturn(Optional.of(payment));

        PaymentResponse response = vnPayService.handleVNPayReturn(request);

        assertThat(response.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        verifyNoInteractions(paymentMessageProducer);
    }

    @Test
    void handleVNPayReturn_successCallback_setsCompletedAndPublishesEvent() {
        Payment payment = buildPayment(PaymentStatus.PENDING);
        VNPayCallbackRequest request = buildSuccessCallback();
        when(vnPayUtil.verifyReturnDataSignature(request)).thenReturn(true);
        when(paymentRepository.findByTransactionId("TXN-001")).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentResponse response = vnPayService.handleVNPayReturn(request);

        assertThat(response.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        verify(paymentMessageProducer).sendPaymentCompletedEvent(any(PaymentCompletedEvent.class));
    }

    @Test
    void handleVNPayReturn_failedCallback_setsFailedAndPublishesFailedEvent() {
        Payment payment = buildPayment(PaymentStatus.PENDING);
        VNPayCallbackRequest request = VNPayCallbackRequest.builder()
                .vnp_TxnRef("TXN-001")
                .vnp_ResponseCode("24")
                .vnp_TransactionStatus("02")
                .vnp_SecureHash("hash")
                .build();
        when(vnPayUtil.verifyReturnDataSignature(request)).thenReturn(true);
        when(paymentRepository.findByTransactionId("TXN-001")).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentResponse response = vnPayService.handleVNPayReturn(request);

        assertThat(response.getStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(paymentMessageProducer).sendPaymentFailedEvent(any(PaymentCompletedEvent.class));
        verify(paymentMessageProducer, org.mockito.Mockito.never()).sendPaymentCompletedEvent(any());
    }

    @Test
    void getRedirectUrlByTransactionId_paymentFound_returnsUrl() {
        Payment payment = buildPayment(PaymentStatus.PENDING);
        payment.setRedirectUrl("http://frontend/result");
        when(paymentRepository.findByTransactionId("TXN-001")).thenReturn(Optional.of(payment));

        String result = vnPayService.getRedirectUrlByTransactionId("TXN-001");

        assertThat(result).isEqualTo("http://frontend/result");
    }

    @Test
    void getRedirectUrlByTransactionId_paymentNotFound_returnsNull() {
        when(paymentRepository.findByTransactionId("TXN-NONE")).thenReturn(Optional.empty());

        String result = vnPayService.getRedirectUrlByTransactionId("TXN-NONE");

        assertThat(result).isNull();
    }

    private Payment buildPayment(PaymentStatus status) {
        return Payment.builder()
                .orderId(orderId)
                .amount(100000L)
                .paymentMethod(PaymentMethod.VNPAY.name())
                .status(status)
                .transactionId("TXN-001")
                .redirectUrl("http://frontend/result")
                .date(LocalDateTime.now())
                .build();
    }

    private VNPayCallbackRequest buildSuccessCallback() {
        return VNPayCallbackRequest.builder()
                .vnp_TxnRef("TXN-001")
                .vnp_ResponseCode("00")
                .vnp_TransactionStatus("00")
                .vnp_SecureHash("hash")
                .build();
    }
}
