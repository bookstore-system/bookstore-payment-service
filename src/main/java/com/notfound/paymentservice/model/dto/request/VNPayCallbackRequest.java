package com.notfound.paymentservice.model.dto.request;

import lombok.*;
import lombok.experimental.FieldDefaults;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class VNPayCallbackRequest {

    String vnp_Amount;
    String vnp_BankCode;
    String vnp_BankTranNo;
    String vnp_CardType;
    String vnp_OrderInfo;
    String vnp_PayDate;
    String vnp_ResponseCode;
    String vnp_TmnCode;
    String vnp_TransactionNo;
    String vnp_TransactionStatus;
    String vnp_TxnRef;
    String vnp_SecureHash;
    String vnp_SecureHashType;


    public boolean isSuccess() {
        return "00".equals(vnp_ResponseCode) && "00".equals(vnp_TransactionStatus);
    }

    public Double getAmountInVND() {
        try {
            return Double.parseDouble(vnp_Amount) / 100;
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    public String getResponseMessage() {
        if (vnp_ResponseCode == null) {
            return "Unknown error";
        }

        return switch (vnp_ResponseCode) {
            case "00" -> "Transaction successful";
            case "07" -> "Transaction suspected of fraud";
            case "09" -> "Card not registered for internet banking";
            case "10" -> "Card authentication failed more than 3 times";
            case "11" -> "Payment timeout. Please try again";
            case "12" -> "Card is locked";
            case "13" -> "Invalid OTP";
            case "24" -> "Transaction canceled";
            case "51" -> "Insufficient account balance";
            case "65" -> "Account exceeded daily transaction limit";
            case "75" -> "Bank is under maintenance";
            case "79" -> "Incorrect payment password more than allowed";
            default -> "Transaction failed: " + vnp_ResponseCode;
        };
    }
}
