package com.notfound.paymentservice.controller;

import com.notfound.paymentservice.exception.GlobalExceptionHandler;
import com.notfound.paymentservice.model.dto.response.PaymentResponse;
import com.notfound.paymentservice.model.enums.PaymentStatus;
import com.notfound.paymentservice.service.MoMoService;
import com.notfound.paymentservice.service.VNPayService;
import com.notfound.paymentservice.service.ZaloPayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {

    @Mock
    private VNPayService vnPayService;

    @Mock
    private ZaloPayService zaloPayService;

    @Mock
    private MoMoService moMoService;

    private MockMvc mockMvc;

    private static final String VNPAY_BASE = "/api/v1/payment/vnpay";
    private static final String ZALOPAY_BASE = "/api/v1/payment/zalopay";
    private static final String MOMO_BASE = "/api/v1/payment/momo";
    private static final String FRONTEND_URL = "http://localhost:3000";

    @BeforeEach
    void setUp() {
        PaymentController controller = new PaymentController(vnPayService, zaloPayService, moMoService);
        ReflectionTestUtils.setField(controller, "frontendUrl", FRONTEND_URL);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ================== VNPay ==================

    @Test
    void handleVNPayCallback_successCallback_redirectsWithResultCode() throws Exception {
        PaymentResponse paymentResponse = PaymentResponse.builder()
                .paymentId(UUID.randomUUID())
                .orderId(UUID.randomUUID())
                .status(PaymentStatus.COMPLETED)
                .build();
        when(vnPayService.handleVNPayReturn(any())).thenReturn(paymentResponse);
        when(vnPayService.getRedirectUrlByTransactionId(anyString()))
                .thenReturn("http://localhost:3000/payment/result");

        mockMvc.perform(get(VNPAY_BASE + "/callback")
                        .param("vnp_TxnRef", "TXN-001")
                        .param("vnp_ResponseCode", "00")
                        .param("vnp_TransactionStatus", "00")
                        .param("vnp_SecureHash", "hash"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("resultCode=00")));
    }

    @Test
    void handleVNPayCallback_noRedirectUrl_fallsBackToFrontendUrl() throws Exception {
        PaymentResponse paymentResponse = PaymentResponse.builder()
                .paymentId(UUID.randomUUID())
                .orderId(UUID.randomUUID())
                .status(PaymentStatus.FAILED)
                .build();
        when(vnPayService.handleVNPayReturn(any())).thenReturn(paymentResponse);
        when(vnPayService.getRedirectUrlByTransactionId(anyString())).thenReturn(null);

        mockMvc.perform(get(VNPAY_BASE + "/callback")
                        .param("vnp_TxnRef", "TXN-001")
                        .param("vnp_ResponseCode", "24")
                        .param("vnp_TransactionStatus", "02")
                        .param("vnp_SecureHash", "hash"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString(FRONTEND_URL)));
    }

    // ================== ZaloPay ==================

    @Test
    void callbackZaloPay_serviceReturnsTrue_respondsReturnCodeOne() throws Exception {
        when(zaloPayService.handleCallback(any())).thenReturn(true);

        mockMvc.perform(post(ZALOPAY_BASE + "/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "data": "{\\"app_trans_id\\":\\"250515_123\\"}",
                                    "mac": "valid-mac"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.return_code", is(1)))
                .andExpect(jsonPath("$.return_message", is("success")));
    }

    @Test
    void callbackZaloPay_serviceReturnsFalse_respondsReturnCodeZero() throws Exception {
        when(zaloPayService.handleCallback(any())).thenReturn(false);

        mockMvc.perform(post(ZALOPAY_BASE + "/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "data": "{\\"app_trans_id\\":\\"250515_123\\"}",
                                    "mac": "bad-mac"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.return_code", is(0)))
                .andExpect(jsonPath("$.return_message", is("fail")));
    }

    // ================== MoMo ==================

    @Test
    void handleMoMoCallback_validCallback_returns200WithCompletedStatus() throws Exception {
        PaymentResponse paymentResponse = PaymentResponse.builder()
                .paymentId(UUID.randomUUID())
                .orderId(UUID.randomUUID())
                .status(PaymentStatus.COMPLETED)
                .amount(150000.0)
                .build();
        when(moMoService.handleMoMoCallback(any())).thenReturn(paymentResponse);

        mockMvc.perform(post(MOMO_BASE + "/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "partnerCode": "MOMO",
                                    "orderId": "ORDER-001",
                                    "requestId": "REQ-001",
                                    "amount": 150000,
                                    "resultCode": 0,
                                    "signature": "valid-sig"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.result.status", is("COMPLETED")));
    }
}
