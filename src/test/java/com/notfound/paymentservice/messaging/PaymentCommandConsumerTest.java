package com.notfound.paymentservice.messaging;

import com.notfound.paymentservice.config.RabbitMQConfig;
import com.notfound.paymentservice.messaging.saga.BaseSagaMessage;
import com.notfound.paymentservice.messaging.saga.CreatePaymentCommand;
import com.notfound.paymentservice.model.dto.response.CreatePaymentResponse;
import com.notfound.paymentservice.model.entity.Payment;
import com.notfound.paymentservice.model.enums.PaymentMethod;
import com.notfound.paymentservice.model.enums.PaymentStatus;
import com.notfound.paymentservice.repository.PaymentRepository;
import com.notfound.paymentservice.repository.ProcessedMessageRepository;
import com.notfound.paymentservice.service.MoMoService;
import com.notfound.paymentservice.service.VNPayService;
import com.notfound.paymentservice.service.ZaloPayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentCommandConsumerTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private ProcessedMessageRepository processedMessageRepository;
    @Mock private PaymentMessageProducer paymentMessageProducer;
    @Mock private VNPayService vnPayService;
    @Mock private MoMoService moMoService;
    @Mock private ZaloPayService zaloPayService;
    @Mock private ObjectMapper objectMapper;

    private PaymentCommandConsumer consumer;
    private UUID sagaId;
    private UUID eventId;
    private UUID orderId;

    @BeforeEach
    void setUp() {
        consumer = new PaymentCommandConsumer(
                paymentRepository,
                processedMessageRepository,
                paymentMessageProducer,
                vnPayService,
                moMoService,
                zaloPayService,
                objectMapper);
        sagaId = UUID.randomUUID();
        eventId = UUID.randomUUID();
        orderId = UUID.randomUUID();
    }

    @Test
    void createCommand_createsVNPayPaymentAndPublishesCreated() throws Exception {
        CreatePaymentCommand command = CreatePaymentCommand.builder()
                .eventId(eventId)
                .sagaId(sagaId)
                .correlationId(sagaId)
                .type(RabbitMQConfig.PAYMENT_CREATE_COMMAND_KEY)
                .orderId(orderId)
                .userId("user-1")
                .paymentMethod("VNPAY")
                .amount(100000D)
                .redirectUrl("http://localhost:3000/result")
                .build();
        Message message = commandMessage(RabbitMQConfig.PAYMENT_CREATE_COMMAND_KEY);
        Payment payment = sagaPayment(PaymentStatus.PENDING);

        when(objectMapper.readValue(message.getBody(), CreatePaymentCommand.class)).thenReturn(command);
        when(processedMessageRepository.existsById(eventId)).thenReturn(false);
        when(paymentRepository.findBySagaId(sagaId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(payment));
        when(vnPayService.createVNPayPaymentUrl(any()))
                .thenReturn(CreatePaymentResponse.builder().paymentUrl("https://pay.test").build());

        consumer.handlePaymentCommand(message);

        verify(vnPayService).createVNPayPaymentUrl(any());
        verify(paymentMessageProducer).publishPaymentCreated(payment, "https://pay.test", eventId);
    }

    @Test
    void duplicateEventId_skipsCreateCommand() throws Exception {
        CreatePaymentCommand command = CreatePaymentCommand.builder()
                .eventId(eventId)
                .sagaId(sagaId)
                .type(RabbitMQConfig.PAYMENT_CREATE_COMMAND_KEY)
                .build();
        Message message = commandMessage(RabbitMQConfig.PAYMENT_CREATE_COMMAND_KEY);

        when(objectMapper.readValue(message.getBody(), CreatePaymentCommand.class)).thenReturn(command);
        when(processedMessageRepository.existsById(eventId)).thenReturn(true);

        consumer.handlePaymentCommand(message);

        verify(vnPayService, never()).createVNPayPaymentUrl(any());
        verify(paymentMessageProducer, never()).publishPaymentCreated(any(), any(), any());
    }

    @Test
    void refundCommand_completedPayment_marksRefundedAndPublishesRefunded() throws Exception {
        BaseSagaMessage command = BaseSagaMessage.builder()
                .eventId(eventId)
                .sagaId(sagaId)
                .correlationId(sagaId)
                .type(RabbitMQConfig.PAYMENT_REFUND_COMMAND_KEY)
                .orderId(orderId)
                .userId("user-1")
                .build();
        Message message = commandMessage(RabbitMQConfig.PAYMENT_REFUND_COMMAND_KEY);
        Payment payment = sagaPayment(PaymentStatus.COMPLETED);

        when(objectMapper.readValue(message.getBody(), BaseSagaMessage.class)).thenReturn(command);
        when(processedMessageRepository.existsById(eventId)).thenReturn(false);
        when(paymentRepository.findBySagaId(sagaId)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(payment)).thenReturn(payment);

        consumer.handlePaymentCommand(message);

        verify(paymentRepository).save(payment);
        verify(paymentMessageProducer).publishPaymentRefunded(payment, eventId);
    }

    private Payment sagaPayment(PaymentStatus status) {
        return Payment.builder()
                .paymentID(UUID.randomUUID())
                .sagaId(sagaId)
                .orderId(orderId)
                .userId("user-1")
                .amount(100000L)
                .paymentMethod(PaymentMethod.VNPAY.name())
                .paymentUrl("https://pay.test")
                .status(status)
                .build();
    }

    private Message commandMessage(String routingKey) {
        return MessageBuilder.withBody(new byte[] { 1 })
                .setReceivedRoutingKey(routingKey)
                .build();
    }
}
