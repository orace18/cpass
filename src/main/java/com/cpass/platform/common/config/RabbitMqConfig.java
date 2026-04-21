package com.cpass.platform.common.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    @Bean
    public Queue dispatchQueue(@Value("${app.messaging.queue}") String queueName) {
        return new Queue(queueName, true);
    }

    @Bean
    public DirectExchange dispatchExchange(@Value("${app.messaging.exchange}") String exchangeName) {
        return new DirectExchange(exchangeName, true, false);
    }

    @Bean
    public Binding dispatchBinding(Queue dispatchQueue,
                                   DirectExchange dispatchExchange,
                                   @Value("${app.messaging.routing-key}") String routingKey) {
        return BindingBuilder.bind(dispatchQueue).to(dispatchExchange).with(routingKey);
    }

    @Bean
    public Queue kycQueue(@Value("${app.kyc.messaging.queue:cpass.kyc.queue}") String queueName) {
        return new Queue(queueName, true);
    }

    @Bean
    public DirectExchange kycExchange(@Value("${app.kyc.messaging.exchange:cpass.kyc.exchange}") String exchangeName) {
        return new DirectExchange(exchangeName, true, false);
    }

    @Bean
    public Binding kycBinding(Queue kycQueue,
                              DirectExchange kycExchange,
                              @Value("${app.kyc.messaging.routing-key:cpass.kyc.process}") String routingKey) {
        return BindingBuilder.bind(kycQueue).to(kycExchange).with(routingKey);
    }

    @Bean
    public Queue webhookQueue(@Value("${app.webhooks.messaging.queue:cpass.webhook.queue}") String queueName) {
        return new Queue(queueName, true);
    }

    @Bean
    public DirectExchange webhookExchange(@Value("${app.webhooks.messaging.exchange:cpass.webhook.exchange}") String exchangeName) {
        return new DirectExchange(exchangeName, true, false);
    }

    @Bean
    public Binding webhookBinding(Queue webhookQueue,
                                  DirectExchange webhookExchange,
                                  @Value("${app.webhooks.messaging.routing-key:cpass.webhook.dispatch}") String routingKey) {
        return BindingBuilder.bind(webhookQueue).to(webhookExchange).with(routingKey);
    }

    @Bean
    public Queue identityVerificationQueue(
            @Value("${app.kyc.verification.messaging.queue:cpass.identity.verification.queue}") String queueName) {
        return new Queue(queueName, true);
    }

    @Bean
    public DirectExchange identityVerificationExchange(
            @Value("${app.kyc.verification.messaging.exchange:cpass.identity.verification.exchange}") String exchangeName) {
        return new DirectExchange(exchangeName, true, false);
    }

    @Bean
    public Binding identityVerificationBinding(Queue identityVerificationQueue,
                                               DirectExchange identityVerificationExchange,
                                               @Value("${app.kyc.verification.messaging.routing-key:cpass.identity.verification.process}") String routingKey) {
        return BindingBuilder.bind(identityVerificationQueue).to(identityVerificationExchange).with(routingKey);
    }

    @Bean
    public Jackson2JsonMessageConverter jackson2JsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
