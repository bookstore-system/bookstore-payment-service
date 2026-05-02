package com.hamtech.bookstorepaymentservice.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentMessageProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private static final String TOPIC = "payment.completed";

    public void sendPaymentCompletedEvent(PaymentCompletedEvent event) {
        log.info("Sending payment completed event for order {} to topic {}", event.getOrderId(), TOPIC);
        kafkaTemplate.send(TOPIC, event.getOrderId().toString(), event);
    }
}
