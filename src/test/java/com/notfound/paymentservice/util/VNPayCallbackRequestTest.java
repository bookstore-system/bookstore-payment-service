package com.notfound.paymentservice.util;

import com.notfound.paymentservice.model.dto.request.VNPayCallbackRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VNPayCallbackRequestTest {

    // ================== isSuccess ==================

    @Test
    void isSuccess_bothCodesAreZero_returnsTrue() {
        VNPayCallbackRequest req = VNPayCallbackRequest.builder()
                .vnp_ResponseCode("00")
                .vnp_TransactionStatus("00")
                .build();

        assertThat(req.isSuccess()).isTrue();
    }

    @Test
    void isSuccess_responseCodeNotZero_returnsFalse() {
        VNPayCallbackRequest req = VNPayCallbackRequest.builder()
                .vnp_ResponseCode("24")
                .vnp_TransactionStatus("00")
                .build();

        assertThat(req.isSuccess()).isFalse();
    }

    @Test
    void isSuccess_transactionStatusNotZero_returnsFalse() {
        VNPayCallbackRequest req = VNPayCallbackRequest.builder()
                .vnp_ResponseCode("00")
                .vnp_TransactionStatus("02")
                .build();

        assertThat(req.isSuccess()).isFalse();
    }

    @Test
    void isSuccess_nullResponseCode_returnsFalse() {
        VNPayCallbackRequest req = VNPayCallbackRequest.builder()
                .vnp_ResponseCode(null)
                .vnp_TransactionStatus("00")
                .build();

        assertThat(req.isSuccess()).isFalse();
    }

    // ================== getAmountInVND ==================

    @Test
    void getAmountInVND_validAmount_dividesByHundred() {
        VNPayCallbackRequest req = VNPayCallbackRequest.builder()
                .vnp_Amount("10000000")
                .build();

        assertThat(req.getAmountInVND()).isEqualTo(100000.0);
    }

    @Test
    void getAmountInVND_invalidFormat_returnsZero() {
        VNPayCallbackRequest req = VNPayCallbackRequest.builder()
                .vnp_Amount("not-a-number")
                .build();

        assertThat(req.getAmountInVND()).isEqualTo(0.0);
    }

    // ================== getResponseMessage ==================

    @Test
    void getResponseMessage_code00_returnsSuccess() {
        VNPayCallbackRequest req = VNPayCallbackRequest.builder()
                .vnp_ResponseCode("00")
                .build();

        assertThat(req.getResponseMessage()).isEqualTo("Transaction successful");
    }

    @Test
    void getResponseMessage_code24_returnsCanceled() {
        VNPayCallbackRequest req = VNPayCallbackRequest.builder()
                .vnp_ResponseCode("24")
                .build();

        assertThat(req.getResponseMessage()).isEqualTo("Transaction canceled");
    }

    @Test
    void getResponseMessage_code51_returnsInsufficientBalance() {
        VNPayCallbackRequest req = VNPayCallbackRequest.builder()
                .vnp_ResponseCode("51")
                .build();

        assertThat(req.getResponseMessage()).isEqualTo("Insufficient account balance");
    }

    @Test
    void getResponseMessage_nullCode_returnsUnknownError() {
        VNPayCallbackRequest req = VNPayCallbackRequest.builder()
                .vnp_ResponseCode(null)
                .build();

        assertThat(req.getResponseMessage()).isEqualTo("Unknown error");
    }

    @Test
    void getResponseMessage_unknownCode_returnsGenericFailed() {
        VNPayCallbackRequest req = VNPayCallbackRequest.builder()
                .vnp_ResponseCode("99")
                .build();

        assertThat(req.getResponseMessage()).isEqualTo("Transaction failed: 99");
    }
}
