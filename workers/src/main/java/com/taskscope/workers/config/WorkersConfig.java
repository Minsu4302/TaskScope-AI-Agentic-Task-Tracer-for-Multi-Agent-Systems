package com.taskscope.workers.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WorkersConfig {

    // observation-enabled: true → Micrometer가 @RabbitListener 수신 시
    // 메시지 헤더의 traceparent를 추출해 dispatcher의 trace에 연결
    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter messageConverter,
            ObservationRegistry observationRegistry) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setObservationEnabled(true);
        return factory;
    }
}
