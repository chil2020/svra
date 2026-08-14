package io.svra.mq;

import java.util.Map;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * worker 端也宣告同一組 topology。兩邊都宣告是為了誰先啟動都能運作，
 * 代價是參數必須嚴格一致。
 */
@Configuration
public class RabbitConfig {

    private final MqProperties mq;

    public RabbitConfig(MqProperties mq) {
        this.mq = mq;
    }

    @Bean
    DirectExchange svraExchange() {
        return new DirectExchange(mq.exchange(), true, false);
    }

    @Bean
    DirectExchange svraDlx() {
        return new DirectExchange(mq.dlx(), true, false);
    }

    /** 掛死信設定：worker reject 時訊息進 DLQ 而非被丟棄，失敗的任務才補償得了。 */
    @Bean
    Queue transcribeJobsQueue() {
        return QueueBuilder.durable(mq.jobQueue())
                .withArguments(Map.of(
                        "x-dead-letter-exchange", mq.dlx(),
                        "x-dead-letter-routing-key", mq.jobRoutingKey()))
                .build();
    }

    @Bean
    Queue transcribeJobsDlq() {
        return QueueBuilder.durable(mq.jobDlq()).build();
    }

    @Bean
    Queue transcribeResultsQueue() {
        return QueueBuilder.durable(mq.resultQueue()).build();
    }

    @Bean
    Binding jobBinding() {
        return BindingBuilder.bind(transcribeJobsQueue()).to(svraExchange()).with(mq.jobRoutingKey());
    }

    @Bean
    Binding dlqBinding() {
        return BindingBuilder.bind(transcribeJobsDlq()).to(svraDlx()).with(mq.jobRoutingKey());
    }

    @Bean
    Binding resultBinding() {
        return BindingBuilder.bind(transcribeResultsQueue()).to(svraExchange()).with(mq.resultRoutingKey());
    }

    /** 預設的轉換器是 Java 原生序列化，Python 端讀不懂。 */
    @Bean
    MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        return template;
    }
}
