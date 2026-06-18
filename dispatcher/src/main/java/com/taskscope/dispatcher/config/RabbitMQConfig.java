package com.taskscope.dispatcher.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE_NAME = "taskscope.exchange";
    public static final String QUEUE_CODE_REVIEW = "taskscope.queue.code-review";
    public static final String QUEUE_SECURITY   = "taskscope.queue.security";
    public static final String QUEUE_TEST_GEN   = "taskscope.queue.test-gen";

    @Bean DirectExchange taskscopeExchange() {
        return new DirectExchange(EXCHANGE_NAME, true, false);
    }

    @Bean Queue codeReviewQueue() { return new Queue(QUEUE_CODE_REVIEW, true); }
    @Bean Queue securityQueue()   { return new Queue(QUEUE_SECURITY,    true); }
    @Bean Queue testGenQueue()    { return new Queue(QUEUE_TEST_GEN,    true); }

    @Bean Binding codeReviewBinding(Queue codeReviewQueue, DirectExchange taskscopeExchange) {
        return BindingBuilder.bind(codeReviewQueue).to(taskscopeExchange).with(QUEUE_CODE_REVIEW);
    }
    @Bean Binding securityBinding(Queue securityQueue, DirectExchange taskscopeExchange) {
        return BindingBuilder.bind(securityQueue).to(taskscopeExchange).with(QUEUE_SECURITY);
    }
    @Bean Binding testGenBinding(Queue testGenQueue, DirectExchange taskscopeExchange) {
        return BindingBuilder.bind(testGenQueue).to(taskscopeExchange).with(QUEUE_TEST_GEN);
    }

    @Bean MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean AmqpTemplate amqpTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }
}
