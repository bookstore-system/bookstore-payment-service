package com.hamtech.bookstorepaymentservice.model.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ZaloPayCallBackResponseDTO {
    @JsonProperty("return_code")
    private int returnCode;
    @JsonProperty("return_message")
    private String returnMessage;
}
