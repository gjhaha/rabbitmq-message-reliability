package com.df.config;


import com.df.entity.User;
import com.rabbitmq.client.Channel;
import org.omg.CORBA.TRANSACTION_MODE;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * @author Lin
 * @create 2020/10/9
 * @since 1.0.0
 * (功能)：
 */
@Configuration
public class ConnectionConfig {

    @Value("${spring.rabbitmq.host}")
    private String host;
    @Value("${spring.rabbitmq.port}")
    private int port;
    @Value("${spring.rabbitmq.username}")
    private String username;
    @Value("${spring.rabbitmq.password}")
    private String password;
    @Value("${spring.rabbitmq.virtual-host}")
    private String virtualHost;

    //初始定义交换机队列
    @Value("${order.queue_name}")
    private String ORDER_QUEUE_NAME;
    @Value("${order.exchange_name}")
    private String ORDER_EXCHANGE_NAME;
    @Value("${order.routing_key_name}")
    private String ORDER_ROUTING_KEY_NAME;

    //第二个定义交换机队列
    @Value("${order1.queue_name}")
    private String ORDER1_QUEUE_NAME;
    @Value("${order1.exchange_name}")
    private String ORDER1_EXCHANGE;
    @Value("${order1.routing_key_name}")
    private String ORDER1_ROUTING_KEY_NAME;

    //死信队列
    @Value("${dead_order.dead_queue}")
    private String DEAD_QUEUE;
    @Value("${dead_order.dead_exchange}")
    private String DEAD_EXCHANGE;
    @Value("dead_order.dead_routing_key")
    private String DEAD_ROUTING_KEY;

    @Autowired
    RabbitmqConfirm rabbitmqConfirm;

    @Bean
    public ConnectionFactory connectionFactory(){
        CachingConnectionFactory factory=new CachingConnectionFactory();
        //设置以单连接，缓存多channel的模式
        factory.setCacheMode(CachingConnectionFactory.CacheMode.CHANNEL);
        //设置默认缓存的channel数量
        factory.setChannelCacheSize(25);
        //设置最大并超时时间，最大不能超过25. 如果超过这个值并且持续超时时间以上，则会出现超时异常
        factory.setChannelCheckoutTimeout(1000);
        factory.setHost(host);
        factory.setPort(port);
        factory.setUsername(username);
        factory.setPassword(password);
        factory.setVirtualHost(virtualHost);
        factory.setPublisherConfirms(true);
        factory.setPublisherReturns(true);
        return factory;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(){
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory());
        //开启后可以使用进入队列的判断。并将错误数据传入message的body中
        rabbitTemplate.setMandatory(true);
        return rabbitTemplate;
    }

    @Bean
    public DirectExchange orderExchange(){
        //创建持久化 非自动删除的叫喊及
        return new DirectExchange(ORDER_EXCHANGE_NAME, true, true);
    }

    @Bean
    public Queue orderQueue(){
        Map<String, Object> map = new HashMap<>();
        //声明当前死信的exchange
        map.put("x-dead-letter-exchange", DEAD_EXCHANGE);
        //声明当前死信的routingkey
        map.put("x-dead-letter-routing-key", DEAD_ROUTING_KEY);
        return new Queue(ORDER_QUEUE_NAME, true, false, false, map);
    }



    //绑定
    @Bean
    public Binding orderBinding(){
        return BindingBuilder.bind(orderQueue()).to(orderExchange()).with(ORDER_ROUTING_KEY_NAME);
    }

    //设置第二个交换机队列
    @Bean
    public DirectExchange order1Exchange(){
        return new DirectExchange(ORDER1_EXCHANGE, true, true);
    }

    @Bean
    public Queue order1Queue(){
        return new Queue(ORDER1_QUEUE_NAME, true, false, false);
    }

    @Bean
    public Binding order1Binding(){
        return BindingBuilder.bind(order1Queue()).to(order1Exchange()).with(ORDER1_ROUTING_KEY_NAME);
    }



    //死信队列参数设置
    @Bean
    public Queue queueDead(){
        return new Queue(DEAD_QUEUE);
    }

    @Bean
    public DirectExchange directExchangeDead(){
        return new DirectExchange(DEAD_EXCHANGE);
    }
    @Bean
    public Binding bindingExchangeQueueDead(){
        return BindingBuilder.bind(queueDead()).to(directExchangeDead()).with(DEAD_ROUTING_KEY);
    }



}













