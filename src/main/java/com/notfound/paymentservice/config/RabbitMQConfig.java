package com.notfound.paymentservice.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJavaTypeMapper;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String PAYMENT_COMPLETED_KEY = "payment.completed";
    public static final String PAYMENT_FAILED_KEY = "payment.failed";
    public static final String COMMAND_EXCHANGE = "bookstore.commands";
    public static final String EVENT_EXCHANGE = "bookstore.events";
    public static final String PAYMENT_COMMANDS_QUEUE = "payment.commands.queue";
    public static final String PAYMENT_CREATE_COMMAND_KEY = "payment.create.command";
    public static final String PAYMENT_REFUND_COMMAND_KEY = "payment.refund.command";
    public static final String PAYMENT_CREATED_KEY = "payment.created";
    public static final String PAYMENT_REFUNDED_KEY = "payment.refunded";

    @Bean
    public TopicExchange commandExchange() {
        return new TopicExchange(COMMAND_EXCHANGE);
    }

    @Bean
    public TopicExchange eventExchange() {
        return new TopicExchange(EVENT_EXCHANGE);
    }

    @Bean
    public Queue paymentCommandsQueue() {
        return new Queue(PAYMENT_COMMANDS_QUEUE, true);
    }

    @Bean
    public Binding paymentCreateCommandBinding(Queue paymentCommandsQueue, TopicExchange commandExchange) {
        return BindingBuilder.bind(paymentCommandsQueue).to(commandExchange).with(PAYMENT_CREATE_COMMAND_KEY);
    }

    @Bean
    public Binding paymentRefundCommandBinding(Queue paymentCommandsQueue, TopicExchange commandExchange) {
        return BindingBuilder.bind(paymentCommandsQueue).to(commandExchange).with(PAYMENT_REFUND_COMMAND_KEY);
    }

    @Bean
    public MessageConverter jacksonJsonMessageConverter() {
        JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter();
        converter.setTypePrecedence(JacksonJavaTypeMapper.TypePrecedence.INFERRED);
        return converter;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jacksonJsonMessageConverter());
        return template;
    }

}
