package com.notfound.paymentservice.messaging;

import com.notfound.paymentservice.config.RabbitMQConfig;
import com.notfound.paymentservice.messaging.saga.BaseSagaMessage;
import com.notfound.paymentservice.messaging.saga.CreatePaymentCommand;
import com.notfound.paymentservice.model.dto.request.PaymentRequest;
import com.notfound.paymentservice.model.dto.response.CreatePaymentResponse;
import com.notfound.paymentservice.model.entity.Payment;
import com.notfound.paymentservice.model.entity.ProcessedMessage;
import com.notfound.paymentservice.model.enums.PaymentStatus;
import com.notfound.paymentservice.repository.PaymentRepository;
import com.notfound.paymentservice.repository.ProcessedMessageRepository;
import com.notfound.paymentservice.service.MoMoService;
import com.notfound.paymentservice.service.VNPayService;
import com.notfound.paymentservice.service.ZaloPayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentCommandConsumer {

    private final PaymentRepository paymentRepository;
    private final ProcessedMessageRepository processedMessageRepository;
    private final PaymentMessageProducer paymentMessageProducer;
    private final VNPayService vnPayService;
    private final MoMoService moMoService;
    private final ZaloPayService zaloPayService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = RabbitMQConfig.PAYMENT_COMMANDS_QUEUE)
    @Transactional
    public void handlePaymentCommand(Message message) {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        try {
            if (RabbitMQConfig.PAYMENT_CREATE_COMMAND_KEY.equals(routingKey)) {
                handleCreate(read(message, CreatePaymentCommand.class));
            } else if (RabbitMQConfig.PAYMENT_REFUND_COMMAND_KEY.equals(routingKey)) {
                handleRefund(read(message, BaseSagaMessage.class));
            } else {
                log.warn("Ignore unsupported payment saga command routingKey={}", routingKey);
            }
        } catch (Exception e) {
            log.error("Unable to handle payment saga command routingKey={}: {}", routingKey, e.getMessage());
        }
    }

    private void handleCreate(CreatePaymentCommand command) {
        try {
            if (!markProcessed(command)) {
                return;
            }
            Payment existing = paymentRepository.findBySagaId(command.getSagaId()).orElse(null);
            if (existing != null) {
                paymentMessageProducer.publishPaymentCreated(existing, existing.getPaymentUrl(), command.getEventId());
                return;
            }

            PaymentRequest request = PaymentRequest.builder()
                    .orderId(command.getOrderId())
                    .sagaId(command.getSagaId())
                    .userId(command.getUserId())
                    .amount(command.getAmount() != null ? command.getAmount().longValue() : 0L)
                    .redirectUrl(command.getRedirectUrl())
                    .build();
            CreatePaymentResponse response = createProviderPayment(command, request);
            Payment payment = paymentRepository.findBySagaId(command.getSagaId())
                    .orElseThrow(() -> new IllegalStateException("Payment was not saved for saga"));
            paymentMessageProducer.publishPaymentCreated(payment, response.getPaymentUrl(), command.getEventId());
        } catch (Exception e) {
            paymentMessageProducer.publishPaymentFailed(command, e.getMessage());
        }
    }

    private CreatePaymentResponse createProviderPayment(CreatePaymentCommand command, PaymentRequest request) {
        String method = command.getPaymentMethod() == null ? "" : command.getPaymentMethod().trim().toUpperCase();
        return switch (method) {
            case "VNPAY" -> vnPayService.createVNPayPaymentUrl(request);
            case "MOMO" -> moMoService.createMoMoPayment(request);
            case "ZALOPAY" -> zaloPayService.createOrderTransaction(request);
            default -> throw new IllegalArgumentException("Unsupported payment method: " + command.getPaymentMethod());
        };
    }

    private void handleRefund(BaseSagaMessage command) {
        try {
            if (!markProcessed(command)) {
                return;
            }
            Payment payment = paymentRepository.findBySagaId(command.getSagaId())
                    .orElseThrow(() -> new IllegalStateException("Payment not found for refund"));
            if (payment.getStatus() == PaymentStatus.COMPLETED) {
                payment.setStatus(PaymentStatus.REFUNDED);
                payment = paymentRepository.save(payment);
            }
            if (payment.getStatus() == PaymentStatus.REFUNDED) {
                paymentMessageProducer.publishPaymentRefunded(payment, command.getEventId());
                return;
            }
            paymentMessageProducer.publishPaymentRefunded(payment, command.getEventId());
        } catch (Exception e) {
            paymentMessageProducer.publishPaymentFailed(command, e.getMessage());
        }
    }

    private <T> T read(Message message, Class<T> targetType) throws Exception {
        return objectMapper.readValue(message.getBody(), targetType);
    }

    private boolean markProcessed(BaseSagaMessage command) {
        if (command.getEventId() == null || command.getSagaId() == null) {
            throw new IllegalArgumentException("eventId and sagaId are required");
        }
        if (processedMessageRepository.existsById(command.getEventId())) {
            log.info("Skip duplicate payment saga command eventId={} type={}", command.getEventId(), command.getType());
            return false;
        }
        processedMessageRepository.save(ProcessedMessage.builder()
                .eventId(command.getEventId())
                .sagaId(command.getSagaId())
                .messageType(command.getType())
                .processedAt(LocalDateTime.now())
                .build());
        return true;
    }
}
