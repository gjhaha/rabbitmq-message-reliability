package com.df.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


/**
 * @author Lin
 * @create 2020/10/10
 * @since 1.0.0
 * (功能)：
 */
@Configuration
public class RedissonConfig  {

    @Bean
    public RedissonClient connectRedissonClient(){
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.44.146:6379").setPassword("7419635");
        config.setCodec(new StringCodec());
        config.setLockWatchdogTimeout(12000);
        RedissonClient redissonClient = Redisson.create(config);
        return redissonClient;
    }

}
