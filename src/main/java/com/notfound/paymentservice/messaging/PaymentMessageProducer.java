package com.notfound.paymentservice.messaging;

import com.notfound.paymentservice.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentMessageProducer {

    private final RabbitTemplate rabbitTemplate;

    public void sendPaymentCompletedEvent(PaymentCompletedEvent event) {
        log.info("Gửi payment.completed event: orderId={}, status={}", event.getOrderId(), event.getStatus());
        rabbitTemplate.convertAndSend(RabbitMQConfig.PAYMENT_EXCHANGE, RabbitMQConfig.PAYMENT_COMPLETED_KEY, event);
    }

    public void sendPaymentFailedEvent(PaymentCompletedEvent event) {
        log.info("Gửi payment.failed event: orderId={}, status={}", event.getOrderId(), event.getStatus());
        rabbitTemplate.convertAndSend(RabbitMQConfig.PAYMENT_EXCHANGE, RabbitMQConfig.PAYMENT_FAILED_KEY, event);
    }
}
