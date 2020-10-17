package com.df.service;

import com.df.config.RabbitmqConfirm;
import com.df.entity.User;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.UUID;

/**
 * @author Lin
 * @create 2020/10/9
 * @since 1.0.0
 * (功能)：
 */
@Component
public class ReceiveMessage {

//    @RabbitListener(queues = "${order.queue_name}")
//    @RabbitHandler
//    public void receiveMessage(User user){
//
//        System.out.println(user);
//        System.out.println("接收端成功接收到信息");
//    }

    @Autowired
    RabbitmqConfirm rabbitmqConfirm;

    //监听的队列
    @Resource
    Queue orderQueue;

    @Resource
    Queue order1Queue;

    //监听的死信队列
    @Resource
    Queue queueDead;

    //正常的消息处理监听逻辑
    @Bean
    public SimpleMessageListenerContainer messageContainer(ConnectionFactory connectionFactory){
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueues(orderQueue, order1Queue);
        //设置一个队列默认有几个消费者
        container.setConcurrentConsumers(3);
        //设置一个队列能最大支持几个消费者  比如别的地方监听该队列
        container.setMaxConcurrentConsumers(5);
        //是否有重复队列
        //container.setDefaultRequeueRejected(false);
        //设置每个channel 每次的接收的消息为10个  默认250
        container.setPrefetchCount(10);

        //设置签收模式，自动签收 AUTO为系统根据处理情况自动签收 MANUAL为手动确认
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        //设置消费者标签
        container.setConsumerTagStrategy(queue -> queue + "_" + UUID.randomUUID().toString());
        //设置默认消息监听
        container.setMessageListener(new ChannelAwareMessageListener() {
            @Override
            public void onMessage(Message message, Channel channel) throws Exception {
                //假象逻辑：可以通过redisson进行次数的判断，再尝试次数之内如果无法处理则加入死信队列，不然重新尝试
                MessageProperties messageProperties = message.getMessageProperties();
                try {
                    byte[] body = message.getBody();
                    User user = (User) rabbitmqConfirm.convertToEntity(body);
                    System.out.println("消费者id: " + message.getMessageProperties().getConsumerTag());
                    System.out.println(message.getMessageProperties().getHeaders().get("spring_returned_message_correlation"));
                    System.out.println("消费者获取数据: " + user);
                    int c = 1 / 0;
                    //通过Tag单个确认  deliveryTag为channel消息的标识，每次发送刷新 ， true代表批量确认同一批次的信息接收成功，为false时代表单独判定某个消息接收成功
                    channel.basicAck(messageProperties.getDeliveryTag(), false);
                }catch (Exception e){
                    //通过tag为该消息进行标识，true为拒绝的消息重新进入队列， false为拒绝后不再进入队列
                    //如果为false，则会进入死信队列， 如果为true则会重新回到队列
                    channel.basicReject(messageProperties.getDeliveryTag(), false);
                }
                Thread.sleep(1000);
            }
        });
        return container;
    }


    //死信队列监听处理逻辑
    @Bean
    public SimpleMessageListenerContainer DeadMessageContainer(ConnectionFactory connectionFactory){
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueues(queueDead);
        container.setConcurrentConsumers(1);
        container.setMaxConcurrentConsumers(3);
        container.setAcknowledgeMode(AcknowledgeMode.AUTO);
        container.setConsumerTagStrategy(queue -> queue + "_" + UUID.randomUUID().toString());
        container.setMessageListener(new ChannelAwareMessageListener() {
            @Override
            public void onMessage(Message message, Channel channel) throws Exception {
                byte[] body = message.getBody();
                User user = (User) rabbitmqConfirm.convertToEntity(body);
                System.out.println("死信队列消息id: " + message.getMessageProperties().getHeaders().get("spring_returned_message_correlation"));
                System.out.println("死信队列获取数据: " + user);

                //后续对死信队列的数据的处理逻辑...
            }
        });
        return container;
    }
}




















