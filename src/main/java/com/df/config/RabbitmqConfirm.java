package com.df.config;

import com.df.controller.ProductController;
import com.df.entity.User;
import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.concurrent.TimeUnit;

/**
 * @author Lin
 * @create 2020/10/10
 * @since 1.0.0
 * (功能)：
 */
@Component  //如果没有需要内部调用，可以使用@Component，不然需要使用@Configuration进行cglib动态代理 调用
public class RabbitmqConfirm {

    @Autowired
    RedissonClient redissonClient;

    @Value("${redisson_operator.error_key}")
    private String ERROR_IN_EXCHANGE;

    @Value("${redisson_operator.error_queue}")
    private String ERROR_IN_QUEUE;

    //如果设置rabbitmq 则会导致confirmCallback()失效,因为初始化先后执行的顺序不同，rabbitTemplate 先生成，需要存入方法，不能自己引用自己。
    @Autowired
    RabbitTemplate rabbitTemplate;

    @Value("${order.exchange_name}")
    private String ORDER_EXCHANGE_NAME;

    @Value("${order.routing_key_name}")
    private String ORDER_ROUTING_KEY_NAME;

    //消息发入交换机确认
    @Bean
    public RabbitTemplate.ConfirmCallback confirmCallback(){
        return new RabbitTemplate.ConfirmCallback() {
            @Override                           //消息唯一标识       //是否成功传入   //原因
            public void confirm(CorrelationData correlationData, boolean b, String s) {
                System.out.println("是否成功传入exchange: " + b);
                //获取交换机的成功情况
                RBucket<Object> bucket = redissonClient.getBucket(ERROR_IN_EXCHANGE + "::" + correlationData.getId());

                //提取User实体数据
                User user = (User) convertToEntity(correlationData.getReturnedMessage().getBody());
                if(b){
                    System.out.println("成功传入交换机,消息id为: " + correlationData.getId());
                    System.out.println("成功传入交换机,数据为: " + user);
                    //删除key
                    bucket.delete();
                }else{
                    System.out.println("传入交换机失败,失败原因: " + s);
                    String times = (String) bucket.get();
                    bucket.expire(10, TimeUnit.SECONDS);
                    Integer numTimes = null;
                    if(times == null || (numTimes = Integer.valueOf(times)) < 5){
                        if(times == null){
                            System.out.println("传入交换机失败次数: " + 1 + " 失败id: " + correlationData.getId());
                            bucket.compareAndSet(null, 1);
                        }else{
                            System.out.println("传入交换机失败次数: " + (numTimes + 1) + "失败id: " + correlationData.getId());
                            bucket.compareAndSet(numTimes, numTimes + 1);
                        }
                        rabbitTemplate.convertAndSend(ORDER_EXCHANGE_NAME, ORDER_ROUTING_KEY_NAME, user, correlationData);
                    }else{
                        System.out.println("消息传入交换机失败,丢弃数据, id: " + correlationData.getId() +
                                "数据: " + user);
                        return;
                    }
                }
            }
        };
    }

    //消息发入队列确认   错误触发后无法再次发送。所以不能循环判断，需要后续处理
    @Bean
    public RabbitTemplate.ReturnCallback setReturnCallBack(){
        return new RabbitTemplate.ReturnCallback() {
            @Override                       //消息体        响应code  响应错误内容   交换机        连接key
            public void returnedMessage(Message message, int i, String s, String s1, String s2) {
                //记录没有成功传入队列的数据，进行后续重传
                User user = (User) convertToEntity(message.getBody());
                System.out.println("队列发送失败");
                System.out.println("没有入队的内容: " + user);
                System.out.println("没有此route");
                System.out.println("错误代码: " + i + " 错误内容: " + s);
                System.out.println("传入交换机: " + s1);
                System.out.println("连接的routeKey:" + s2);
                String correlationId = (String) message.getMessageProperties().getHeaders().get("spring_returned_message_correlation");
                RMap<Object, Object> map = redissonClient.getMap(ERROR_IN_QUEUE + "::" + correlationId);
                map.put("exchange", s1);
                map.put("routeKey", s2);
                map.put("message", message);
                map.put("errorDetail", s);
                map.expire(2, TimeUnit.HOURS);
            }
        };
    }


    //将byte数组反序列化为实体
    public Object convertToEntity(byte[] bytes){
        ByteArrayInputStream byteArray = new ByteArrayInputStream(bytes);
        Object result = null;
        try {
            ObjectInputStream ois = new ObjectInputStream(byteArray);
            Object object = ois.readObject();
            result = object;
            ois.close();
            byteArray.close();
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        }
        return result;
    }
}
