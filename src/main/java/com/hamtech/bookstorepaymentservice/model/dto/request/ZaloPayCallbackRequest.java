package com.hamtech.bookstorepaymentservice.model.dto.request;

import lombok.*;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class ZaloPayCallbackRequest {
    private String data;
    private String mac;
}
