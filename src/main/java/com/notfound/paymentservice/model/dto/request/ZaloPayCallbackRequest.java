package com.notfound.paymentservice.model.dto.request;

import lombok.*;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class ZaloPayCallbackRequest {
    private String data;
    private String mac;
}
