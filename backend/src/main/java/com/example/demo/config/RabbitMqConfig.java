package com.example.demo.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * v1.2 §10.2.1：交换机与命名空间从 wms.* 重命名为 sales.* / inventory.*。
 * 兼容期保留旧常量（wmsTopicExchange 等）以避免下游消费者立刻断链。
 */
@Configuration
public class RabbitMqConfig {

    public static final String EXCHANGE = "sales.topic";
    public static final String DEAD_LETTER_EXCHANGE = "sales.dlx";
    public static final String ORDER_EVENTS_QUEUE = "sales.order.events";
    public static final String STOCK_ALERT_QUEUE = "sales.stock.alerts";
    public static final String DEAD_LETTER_QUEUE = "sales.dead-letter";
    /** 旧版本命名保留，避免重复声明 Exchange 冲突 */
    public static final String LEGACY_EXCHANGE = "wms.topic";
    public static final String LEGACY_DEAD_LETTER_EXCHANGE = "wms.dlx";

    @Bean
    public TopicExchange salesTopicExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange salesDeadLetterExchange() {
        return new TopicExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    public Queue orderEventsQueue() {
        return QueueBuilder.durable(ORDER_EVENTS_QUEUE)
            .deadLetterExchange(DEAD_LETTER_EXCHANGE)
            .deadLetterRoutingKey("dead.order")
            .build();
    }

    @Bean
    public Queue stockAlertQueue() {
        return QueueBuilder.durable(STOCK_ALERT_QUEUE)
            .deadLetterExchange(DEAD_LETTER_EXCHANGE)
            .deadLetterRoutingKey("dead.stock")
            .build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding orderEventsBinding(Queue orderEventsQueue, TopicExchange salesTopicExchange) {
        return BindingBuilder.bind(orderEventsQueue).to(salesTopicExchange).with("sales.#");
    }

    @Bean
    public Binding stockLowStockBinding(Queue stockAlertQueue, TopicExchange salesTopicExchange) {
        return BindingBuilder.bind(stockAlertQueue).to(salesTopicExchange).with("inventory.low_stock");
    }

    @Bean
    public Binding stockOverStockBinding(Queue stockAlertQueue, TopicExchange salesTopicExchange) {
        return BindingBuilder.bind(stockAlertQueue).to(salesTopicExchange).with("inventory.over_stock");
    }

    @Bean
    public Binding stockExpiringBinding(Queue stockAlertQueue, TopicExchange salesTopicExchange) {
        return BindingBuilder.bind(stockAlertQueue).to(salesTopicExchange).with("inventory.expiring");
    }

    @Bean
    public Binding stockRestockBinding(Queue stockAlertQueue, TopicExchange salesTopicExchange) {
        return BindingBuilder.bind(stockAlertQueue).to(salesTopicExchange).with("inventory.stock.restock");
    }

    @Bean
    public Binding deadLetterBinding(Queue deadLetterQueue, TopicExchange salesDeadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(salesDeadLetterExchange).with("dead.#");
    }
}