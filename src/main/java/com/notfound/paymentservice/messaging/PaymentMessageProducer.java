package com.notfound.paymentservice.messaging;

import com.notfound.paymentservice.config.RabbitMQConfig;
import com.notfound.paymentservice.messaging.saga.BaseSagaMessage;
import com.notfound.paymentservice.messaging.saga.PaymentCompletedSagaEvent;
import com.notfound.paymentservice.messaging.saga.PaymentCreatedEvent;
import com.notfound.paymentservice.messaging.saga.SagaFailureEvent;
import com.notfound.paymentservice.model.entity.Payment;
import com.notfound.paymentservice.model.enums.PaymentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentMessageProducer {

    private final RabbitTemplate rabbitTemplate;

    public void sendPaymentCompletedEvent(PaymentCompletedEvent event) {
        if (event.getSagaId() != null) {
            log.info("Publish saga payment.completed: sagaId={}, paymentId={}", event.getSagaId(), event.getPaymentId());
            rabbitTemplate.convertAndSend(RabbitMQConfig.EVENT_EXCHANGE, RabbitMQConfig.PAYMENT_COMPLETED_KEY,
                    PaymentCompletedSagaEvent.builder()
                            .eventId(UUID.randomUUID())
                            .sagaId(event.getSagaId())
                            .correlationId(event.getSagaId())
                            .type(RabbitMQConfig.PAYMENT_COMPLETED_KEY)
                            .occurredAt(LocalDateTime.now())
                            .orderId(event.getOrderId())
                            .userId(event.getUserId())
                            .paymentId(event.getPaymentId())
                            .build(),
                    this::removeJavaTypeHeaders);
            return;
        }
        log.info("Skip legacy payment.completed publish because sagaId is null: orderId={}", event.getOrderId());
    }

    public void sendPaymentFailedEvent(PaymentCompletedEvent event) {
        if (event.getSagaId() != null) {
            log.warn("Publish saga payment.failed: sagaId={}, paymentId={}", event.getSagaId(), event.getPaymentId());
            rabbitTemplate.convertAndSend(RabbitMQConfig.EVENT_EXCHANGE, RabbitMQConfig.PAYMENT_FAILED_KEY,
                    SagaFailureEvent.builder()
                            .eventId(UUID.randomUUID())
                            .sagaId(event.getSagaId())
                            .correlationId(event.getSagaId())
                            .type(RabbitMQConfig.PAYMENT_FAILED_KEY)
                            .occurredAt(LocalDateTime.now())
                            .orderId(event.getOrderId())
                            .userId(event.getUserId())
                            .reason("Payment failed")
                            .build(),
                    this::removeJavaTypeHeaders);
            return;
        }
        log.info("Skip legacy payment.failed publish because sagaId is null: orderId={}", event.getOrderId());
    }

    public void publishPaymentCreated(Payment payment, String paymentUrl, UUID causationId) {
        log.info("Publish saga payment.created: sagaId={}, paymentId={}", payment.getSagaId(), payment.getPaymentID());
        rabbitTemplate.convertAndSend(RabbitMQConfig.EVENT_EXCHANGE, RabbitMQConfig.PAYMENT_CREATED_KEY,
                PaymentCreatedEvent.builder()
                        .eventId(UUID.randomUUID())
                        .sagaId(payment.getSagaId())
                        .correlationId(payment.getSagaId())
                        .causationId(causationId)
                        .type(RabbitMQConfig.PAYMENT_CREATED_KEY)
                        .occurredAt(LocalDateTime.now())
                        .orderId(payment.getOrderId())
                        .userId(payment.getUserId())
                        .paymentId(payment.getPaymentID())
                        .paymentUrl(paymentUrl)
                        .build(),
                this::removeJavaTypeHeaders);
    }

    public void publishPaymentResult(Payment payment, UUID causationId) {
        if (payment.getSagaId() == null) {
            log.info("Skip legacy payment result publish because sagaId is null: paymentId={}", payment.getPaymentID());
            return;
        }

        if (payment.getStatus() == PaymentStatus.COMPLETED) {
            log.info("Publish saga payment.completed: sagaId={}, paymentId={}", payment.getSagaId(), payment.getPaymentID());
            rabbitTemplate.convertAndSend(RabbitMQConfig.EVENT_EXCHANGE, RabbitMQConfig.PAYMENT_COMPLETED_KEY,
                    buildPaymentResultEvent(payment, RabbitMQConfig.PAYMENT_COMPLETED_KEY, causationId),
                    this::removeJavaTypeHeaders);
        } else if (payment.getStatus() == PaymentStatus.FAILED) {
            publishPaymentFailed(payment, causationId, "Payment failed");
        }
    }

    public void publishPaymentRefunded(Payment payment, UUID causationId) {
        log.info("Publish saga payment.refunded: sagaId={}, paymentId={}", payment.getSagaId(), payment.getPaymentID());
        rabbitTemplate.convertAndSend(RabbitMQConfig.EVENT_EXCHANGE, RabbitMQConfig.PAYMENT_REFUNDED_KEY,
                buildPaymentResultEvent(payment, RabbitMQConfig.PAYMENT_REFUNDED_KEY, causationId),
                this::removeJavaTypeHeaders);
    }

    public void publishPaymentFailed(Payment payment, UUID causationId, String reason) {
        log.warn("Publish saga payment.failed: sagaId={}, paymentId={}, reason={}",
                payment.getSagaId(), payment.getPaymentID(), reason);
        rabbitTemplate.convertAndSend(RabbitMQConfig.EVENT_EXCHANGE, RabbitMQConfig.PAYMENT_FAILED_KEY,
                SagaFailureEvent.builder()
                        .eventId(UUID.randomUUID())
                        .sagaId(payment.getSagaId())
                        .correlationId(payment.getSagaId())
                        .causationId(causationId)
                        .type(RabbitMQConfig.PAYMENT_FAILED_KEY)
                        .occurredAt(LocalDateTime.now())
                        .orderId(payment.getOrderId())
                        .userId(payment.getUserId())
                        .reason(reason)
                        .build(),
                this::removeJavaTypeHeaders);
    }

    public void publishPaymentFailed(BaseSagaMessage command, String reason) {
        log.warn("Publish saga payment.failed: sagaId={}, orderId={}, reason={}",
                command.getSagaId(), command.getOrderId(), reason);
        rabbitTemplate.convertAndSend(RabbitMQConfig.EVENT_EXCHANGE, RabbitMQConfig.PAYMENT_FAILED_KEY,
                SagaFailureEvent.builder()
                        .eventId(UUID.randomUUID())
                        .sagaId(command.getSagaId())
                        .correlationId(command.getCorrelationId())
                        .causationId(command.getEventId())
                        .type(RabbitMQConfig.PAYMENT_FAILED_KEY)
                        .occurredAt(LocalDateTime.now())
                        .orderId(command.getOrderId())
                        .userId(command.getUserId())
                        .reason(reason)
                        .build(),
                this::removeJavaTypeHeaders);
    }

    private PaymentCompletedSagaEvent buildPaymentResultEvent(Payment payment, String type, UUID causationId) {
        return PaymentCompletedSagaEvent.builder()
                .eventId(UUID.randomUUID())
                .sagaId(payment.getSagaId())
                .correlationId(payment.getSagaId())
                .causationId(causationId)
                .type(type)
                .occurredAt(LocalDateTime.now())
                .orderId(payment.getOrderId())
                .userId(payment.getUserId())
                .paymentId(payment.getPaymentID())
                .build();
    }

    private Message removeJavaTypeHeaders(Message message) {
        message.getMessageProperties().getHeaders().remove("__TypeId__");
        message.getMessageProperties().getHeaders().remove("__ContentTypeId__");
        message.getMessageProperties().getHeaders().remove("__KeyTypeId__");
        return message;
    }
}
