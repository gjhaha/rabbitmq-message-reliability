package com.df.controller;

import com.df.entity.User;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.*;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * @author Lin
 * @create 2020/10/9
 * @since 1.0.0
 * (功能)：
 */
@RestController
public class ProductController {

    @Autowired
    private RabbitTemplate rabbitTemplate;


    @Value("${order.exchange_name}")
    private String ORDER_EXCHANGE_NAME;
    @Value("${order.routing_key_name}")
    private String ORDER_ROUTING_KEY_NAME;

    @Value("${order1.exchange_name}")
    private String ORDER1_EXCHANGE;
    @Value("${order1.routing_key_name}")
    private String ORDER1_ROUTING_KEY_NAME;

    @Autowired
    RabbitTemplate.ConfirmCallback confirmCallback;

    @Autowired
    RabbitTemplate.ReturnCallback returnCallBack;

    @GetMapping(value = "/send")
    public String send(){
        rabbitTemplate.setConfirmCallback(confirmCallback);
        rabbitTemplate.setReturnCallback(returnCallBack);
        User user = new User(1L, "李姐", "888888@qq.com", "1737766562");
        //String message = "我要发送信息啦";

            CorrelationData correlationData = getCorrelationData(user);
            rabbitTemplate.convertAndSend(ORDER_EXCHANGE_NAME, ORDER_ROUTING_KEY_NAME, user, correlationData);


//        User user1 = new User(2L, "JY", "氢气请求群.com", "15892153");
//        CorrelationData correlationData1 = getCorrelationData(user1);
//        rabbitTemplate.convertAndSend(ORDER1_EXCHANGE, ORDER1_ROUTING_KEY_NAME, user1, correlationData1);
        return "成功发送信息";
    }

    private CorrelationData getCorrelationData(Object object){
        CorrelationData correlationData = new CorrelationData(UUID.randomUUID().toString());
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        Message message = new Message(serializableEntity(object), new MessageProperties());
        correlationData.setReturnedMessage(message);
        return correlationData;
    }

    //序列化对象
    private byte[] serializableEntity(Object object){
        ByteArrayOutputStream byteArray = new ByteArrayOutputStream();
        try {
            ObjectOutputStream ops = new ObjectOutputStream(byteArray);
            ops.writeObject(object);
            ops.close();
            byteArray.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return byteArray.toByteArray();
    }

}
