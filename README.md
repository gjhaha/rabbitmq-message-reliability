## 消息可靠性
在我们平时开发的过程中往往会有使用到rabbitmq，通过rabbitmq进行消息的转发，可是消息在发送的过程中真的是**可靠**的吗？
![在这里插入图片描述](https://img-blog.csdnimg.cn/20201017090423863.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L3FxXzQxNzYyNTk0,size_16,color_FFFFFF,t_70#pic_center)
由上图可以看到，消息从Producer到Consumer需要经过Broker，内部需要将消息先经过绑定的exchange，再根据exchange发送到指定的Queue，最后由Consumer从队列中获取到消息进行消费。

如果需要保证消息的**尽量**不丢失，就得从这几个流程中下手。总的来说可以分为三个阶段的处理：
 1. 消息从Producer发送到Queue之前，需要保证消息在发送的过程中不丢失
 2. 消息发送到Queue的时候，需要对Queue和消息进行持久化的操作，防止消息丢失
 3. 消息从Queue取出到Consumer被消费的过程中需要进行手动确认消息消费
 
 完成以上几个步骤后可以达到消息大概率不丢失的情况了，但也不是百分百不丢失，如果大规模服务节点崩溃，那还是不能避免消息的丢失，我们只能尽量保证。

**以下会简单的介绍下各阶段的保证机制以及制作一个小demo进行认证，环境是SpringBoot + rabbitmq + redisson(附加，可不用).**

## 发布确认机制
这个阶段也就是由消息从Producer发出，通过Exchange进入到Queue的一个过程。首先要解决消息从Producer到Exchange的可靠性。
### ConfirmCallback 回调确认消息
当需要确认消息是否成功发送到 Exchange 的时候。使用该函数，系统推送消息后，该线程便会得到释放，等 Exchange 接收到消息后系统便会异步调用 ConfirmCallback 绑定的方法进行处理。ConfirmCallback绑定了一个方法confirm。confirm方法会将每个消息的标识**correlationData**(需要自己设置，允许为null)， 是否成功传入交换机**var2**，和失败的原因**var3**进行传入。如果成功进入交换机，**var2**为true， **var3**为null，反之**var2**为false，**var3**为具体原因。
```java
					   //消息的标识			//是否成功传入交换机		//没有传入的原因
void confirm(@Nullable CorrelationData var1, boolean var2, @Nullable String var3);
```
### 举例
使用Springboot进行操作，先导入pom依赖以及设置配置文件。 redisson可不用
```java
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>2.1.4.RELEASE</version>
    </parent>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-amqp</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>

        <dependency>
            <groupId>org.redisson</groupId>
            <artifactId>redisson</artifactId>
            <version>3.11.1</version>
        </dependency>
    </dependencies>
```
application.yml配置
```java
server:
  port: 8082

spring:
  application:
    name: SpringAmqpTest
  rabbitmq:
    host: 127.0.0.1
    port: 5672
    username: guest
    password: guest
    virtual-host: /SpringAmqpTest


order:
  queue_name: direct.first
  exchange_name: directExchange
  routing_key_name: directKey1
```
设置rabbitmq的基础配置，生成队列，交换机等。

```java
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

    @Autowired
    RabbitmqConfirm rabbitmqConfirm;

    @Bean
    public ConnectionFactory connectionFactory(){
        CachingConnectionFactory factory=new CachingConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        factory.setUsername(username);
        factory.setPassword(password);
        factory.setVirtualHost(virtualHost);
        //***开启发布确认机制
        factory.setPublisherConfirms(true);
        return factory;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(){
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory());
        return rabbitTemplate;
    }

    @Bean
    public DirectExchange orderExchange(){
        //创建持久化 非自动删除的交换机
        return new DirectExchange(ORDER_EXCHANGE_NAME, true, true);
    }

    @Bean
    public Queue orderQueue(){
    	//生成队列
        return new Queue(ORDER_QUEUE_NAME, true, false, false);
    }
    
    //绑定
    @Bean
    public Binding orderBinding(){
        return BindingBuilder.bind(orderQueue()).to(orderExchange()).with(ORDER_ROUTING_KEY_NAME);
    }
}
```
有设置redisson的同学可以配置下redisson，没有的不配也不影响

```java
@Configuration
public class RedissonConfig  {

    @Bean
    public RedissonClient connectRedissonClient(){
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.4.13:6379").setPassword("7419635");
        config.setCodec(new StringCodec());
        config.setLockWatchdogTimeout(12000);
        RedissonClient redissonClient = Redisson.create(config);
        return redissonClient;
    }

}
```

配置ConfirmCallback回调确认机制， 使用redisson进行了多次尝试回调确认，如果没有配置redisson可以做别的判断尝试。
```java
@Component
public class RabbitmqConfirm {

    @Autowired
    RedissonClient redissonClient;
    
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
```
消息发送方发送消息配置

```java
@RestController
public class ProductController {

    @Autowired
    private RabbitTemplate rabbitTemplate;


    @Value("${order.exchange_name}")
    private String ORDER_EXCHANGE_NAME;
    @Value("${order.routing_key_name}")
    private String ORDER_ROUTING_KEY_NAME;

    @Autowired
    RabbitTemplate.ConfirmCallback confirmCallback;

    @GetMapping(value = "/send")
    public String send(){
    	//设置确认回调函数
        rabbitTemplate.setConfirmCallback(confirmCallback);
        User user = new User(1L, "李姐", "888888@qq.com", "1731111");
        
        CorrelationData correlationData = getCorrelationData(user);
        rabbitTemplate.convertAndSend(ORDER_EXCHANGE_NAME, ORDER_ROUTING_KEY_NAME, user, correlationData);
        return "成功发送信息";
    }
	//手动生成唯一标识
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
```
简单的接收方监听消息

```java
@Component
public class ReceiveMessage {

    @RabbitListener(queues = "${order.queue_name}")
    @RabbitHandler
    public void receiveMessage(User user){

        System.out.println(user);
        System.out.println("接收端成功接收到信息");
    }
}
```
启动执行一下，当Exchange输入正确的时候
![在这里插入图片描述](https://img-blog.csdnimg.cn/20201017103120563.png#pic_center)
我们将要传入的Exchange设置成一个不存在的值，看下结果。可以看到传入失败后会显示失败的原因，然后执行之后的逻辑，我在逻辑中设置重新发送到正确的交换机，则执行成功！
![在这里插入图片描述](https://img-blog.csdnimg.cn/20201017103514808.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L3FxXzQxNzYyNTk0,size_16,color_FFFFFF,t_70#pic_center)

### ReturnCallback 处理进入队列的情况
以上通过**ConfirmCallback**解决从producer到Exchange可能出现消息丢失，Exchange无效的情况，现在用一样的方式通过**ReturnCallback**来处理从Exchange到Queue的消息可靠性情况。如果队列错误绑定不存在的 Queue，或者 Broken 出现问题末能找到对应的 Queue，会调用 **ReturnCallback** 的回调函数来进行错误处理。

如果出现没有进入Queue的情况则会调用内部的**returnedMessage**方法，带有几个参数。**Message var1** 为传入的Message消息体，**int var2** 为错误响应的code， **String var3** 为错误的原因， **String var4** 为传入的交换机， **String var5** 为绑定的routingKey。
```java
    public interface ReturnCallback {
        void returnedMessage(Message var1, int var2, String var3, String var4, String var5);
    }
```
总的配置和上述的**ConfirmCallback**差不多，再加点**ReturnCallback**的逻辑就可以。因为篇幅太长了..就不重新再拉一次了，最后会把代码放上来，直接看完整的代码就好。
```java
	//和confirmCallback一样需要设置返回队列为true
	factory.setPublisherReturns(true);

	//并且需要开启Mandatory，会将传入的数据保存回body中
	@Bean
    public RabbitTemplate rabbitTemplate(){
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory());
        //开启后可以使用进入队列的判断。并将数据传入message的body中
        rabbitTemplate.setMandatory(true);
        return rabbitTemplate;
    }
 
 	//和confirmCallback 一样进行设置逻辑
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
	
	//在消息发送的controller进入注入，并设置
    @Autowired
    RabbitTemplate.ReturnCallback returnCallBack;
	//在发送逻辑中设置
	rabbitTemplate.setReturnCallback(returnCallBack);
```
运行来看一波结果，将交换机设置对后，设置转发错误的RoutingKey，这样就不能成功的进入队列。可以看到结果，成功进入到交换机但是没有通过正确的RoutingKey进入到队列，所以**confirmCallback**，反馈正确，而**ReturnCallback**，进行了错误的反馈，内容的NO-ROUTE。后续可以将错误信息等保存处理定位。
![在这里插入图片描述](https://img-blog.csdnimg.cn/20201017111752619.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L3FxXzQxNzYyNTk0,size_16,color_FFFFFF,t_70#pic_center)

## 队列消息持久化机制
以上已经成功的将消息从producer发送到了Queue，离我们的成功已经实现了三分之一。现在要考虑的是，消息到达队列后，如果RabbitMQ突然出现了什么问题进行了服务重启，那么Queue内部所保存着的消息是否还存在。所以需要设置Queue和消息的持久化，避免出现这种消息丢失的情况。
### 队列持久化
队列的持久化在我们对队列进行定义的时候就可以设置。主要的参数有： **String name** 设置队列的名称， **boolean durable** 设置是否持久化，true为持久化， **boolean exclusive** 设置是否排他，默认为false，如果设置为true则持久化不起作用，代表为只有自己可见的队列，即不允许其它用户访问。 **boolean autoDelete** 消息离开队列后是否自动删除，true为自动删除，建议设置为false。 **Map<String, Object> arguments** 可以为队列标记一些属性，比如绑定死信队列(后续讲)
```java		
    public Queue(String name, boolean durable, boolean exclusive, boolean autoDelete, Map<String, Object> arguments) {
        Assert.notNull(name, "'name' cannot be null");
        this.name = name;
        this.actualName = StringUtils.hasText(name) ? name : Base64UrlNamingStrategy.DEFAULT.generateName() + "_awaiting_declaration";
        this.durable = durable;
        this.exclusive = exclusive;
        this.autoDelete = autoDelete;
        this.arguments = (Map)(arguments != null ? arguments : new HashMap());
    }
```
队列就按之前给的设置即可。设置成持久化且非自动删除消息的队列。
```java
    @Bean
    public Queue orderQueue(){
        Map<String, Object> map = new HashMap<>();
        return new Queue(ORDER_QUEUE_NAME, true, false, false);
    }
```
### 消息持久化
根据以上可以将队列设置为持久化，但是存储在队列中的消息，如果没有设置持久化的话，服务器关闭，消息一样会进行丢失。所以，消息的持久化设置也很有必要。

消息的持久化相比于队列的持久化设置会有一点麻烦，是在消息Message定义的时候就进行的设置。以下是一段我们生成数据标识的代码。
```java
												//需要发送的对象
    private CorrelationData getCorrelationData(Object object){
    	//生成correlationData，并设置UUID为唯一标识
        CorrelationData correlationData = new CorrelationData(UUID.randomUUID().toString());
        //生成消息的配置，
        MessageProperties messageProperties = new MessageProperties();
        //在配置中设置持久化， MessageDeliveryMode.PERSISTENT 代表持久化模式
        messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        //将message的配置 和 将对象序列化成byte[]格式，生成Message
        Message message = new Message(serializableEntity(object), new MessageProperties());
        //将message封装到correlationData，返回
        correlationData.setReturnedMessage(message);
        return correlationData;
    }
```
可以看到持久化是在MessageProperties中设置，通过设置MessageDeliveryMode.PERSISTENT 或者 MessageDeliveryMode.NON_PERSISTENT 决定是否持久化该消息。
### 交换机持久化
交换机如果不持久化，那么在rabbitmq进行重启的时候，会丢失关于该交换机的一些信息，虽然对消息的可靠并没有什么影响，但还是建议对其进行持久化操作，持久化的方式也很简单。
```java
	//构造时将durable设置为true即可
    public DirectExchange(String name, boolean durable, boolean autoDelete) {
        super(name, durable, autoDelete);
    }
```
## 消费方确认机制
到达最后一个步骤，Consumer从Queue中取出消息进行处理的时候，应该保证消息的不可丢失，不能因为Consumer方在处理的过程中出现异常等情况，导致消息没有成功处理而丢失。主要采用的是一个手动确认的方式，仅仅在逻辑处理完全后，再向Queue发送确认信息，从Queue中清除对应的消息。
### SimpleMessageListenerContainer
在Consumer进行消息的确认之前，我们重新的定义下消息接收的工具，我们采用SimpleMessageListenerContainer对消息进行接收。SimpleMessageListenerContainer相当于是rabbitmq进行封装好的一个容器，其本身并没有直接对消息进行处理，而是把消息的处理方式交给了内部的**MessageListener**，而SimpleMessageListenerContainer则可以做到定义接收的Consumer数量以及最多处理多少未确认的消息等功能。来看一下具体的实现，直接在原来的接收类上修改。

```java
@Component
public class ReceiveMessage {

//    @RabbitListener(queues = "${order.queue_name}")
//    @RabbitHandler
//    public void receiveMessage(User user){
//
//        System.out.println(user);
//        System.out.println("接收端成功接收到信息");
//    }

    //监听的队列
    @Resource
    Queue orderQueue;
	//新建了另一个队列
    @Resource
    Queue order1Queue;

    //正常的消息处理监听逻辑
    @Bean
    public SimpleMessageListenerContainer messageContainer(ConnectionFactory connectionFactory){
    	//生成 SimpleMessageListenerContainer
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        //设置监听的队列
        container.setQueues(orderQueue, order1Queue);
        //设置一个监听的队列默认有几个消费者
        container.setConcurrentConsumers(3);
        //设置一个队列能最大支持几个消费者
        container.setMaxConcurrentConsumers(5);
        //设置每个channel 最多可以处理10个正在处理的消息
        container.setPrefetchCount(10);

        //设置签收模式，自动签收 AUTO为系统根据处理情况自动签收 MANUAL为手动确认
        container.setAcknowledgeMode(AcknowledgeMode.AUTO);
        //设置消费者标签， 为消费者的唯一标识
        container.setConsumerTagStrategy(queue -> queue + "_" + UUID.randomUUID().toString());
        //设置默认消息监听，如果监听到队列中有消息，则会处理下面的逻辑
        container.setMessageListener(new ChannelAwareMessageListener() {
            @Override
            public void onMessage(Message message, Channel channel) throws Exception {
            	//从队列中传入对应的message 和 channel
            }
        });
        return container;
    }
```
我们重启一下项目，去看看rabbitmq有什么不同。由于我们设置监听两个Queue，并且每个Queue都设置了3个消费者，可以从图上看到，一共是6个消费者，3个channel，6个消费者共享。
![在这里插入图片描述](https://img-blog.csdnimg.cn/20201017144021767.png#pic_center)
每个channel最多可以处理10个未处理的消息。
![在这里插入图片描述](https://img-blog.csdnimg.cn/20201017144045286.png#pic_center)
### 确认机制
由于我们使用的是SpringBoot整合Rabbitmq，也就是Spring Amqp。我们的消息确认机制，建立在SimpleMessageListenerContainer上，只需要做一些设置，即可完成。

Consumer的消息确认机制通过设置**AcknowledgeMode** 来完成，一共有三种模式，AUTO，MANUAL，NONE。其中如果没有进行自己设定，那么默认就是AUTO自动确认模式，MANUAL为手动确认。在默认的**AUTO**模式下。如果方法正常执行结束，则会默认返回ack，如果出现异常，且是**AmqpRejectAndDontRequeueException**，则不会重回队列，如果是其他的异常，则会nack重回队列重新消费。

如果将**AcknowledgeMode**设置为MANUAL，那么则需要手动来控制逻辑。主要有这么几个channel类的方法控制：
```java
	// deliveryTag 为该消息的标识， multiple为true代表批量确认同一批次的消息成功接收，false代表单独判定某个消息接收成功。
	void basicAck(long deliveryTag, boolean multiple)
	// deliveryTag 为该消息的标识， requeue为true时，消息会重回队列，如果为false，则丢弃该消息
	void basicReject(long deliveryTag, boolean requeue)
	//deliveryTag 为该消息的标识， multiple为true的话代表确认该一批消息接收失败，false的话代表单独判定某个消息接收失败
	//requeue为true的话代表重回队列， false为丢弃，不再进去队列
	void basicNack(long deliveryTag, boolean multiple, boolean requeue)
```
了解一下后我们对consumer确认进行实操。同样是上面的代码，现在我们将其设置为MANUAL手动签收。并且设置ack和basicReject进行确认，设置1/0出现异常看看是否会重回队列。
```java
    //正常的消息处理监听逻辑
    @Bean
    public SimpleMessageListenerContainer messageContainer(ConnectionFactory connectionFactory){
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueues(orderQueue, order1Queue);
        //设置一个队列默认有几个消费者
        container.setConcurrentConsumers(3);
        //设置一个队列能最大支持几个消费者  比如别的地方监听该队列
        container.setMaxConcurrentConsumers(5);
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
                    //获取到传输的对象实体
                    User user = (User) rabbitmqConfirm.convertToEntity(body);
                    System.out.println("消费者id: " + message.getMessageProperties().getConsumerTag());
                    System.out.println(message.getMessageProperties().getHeaders().get("spring_returned_message_correlation"));
                    System.out.println("消费者获取数据: " + user);
                    int i = 1 / 0;
                    //通过Tag单个确认  deliveryTag为channel消息的标识，每次发送刷新 ， true代表批量确认同一批次的信息接收成功，为false时代表单独判定某个消息接收成功
                    channel.basicAck(messageProperties.getDeliveryTag(), false);
                }catch (Exception e){
                    //通过tag为该消息进行标识，true为拒绝的消息重新进入队列， false为拒绝后不再进入队列
                    channel.basicReject(messageProperties.getDeliveryTag(), true);
                }
            }
        });
        return container;
    }
```
运行结果：可以看到，在出现异常后就一直重回队列，继续消费。将**channel.basicReject(messageProperties.getDeliveryTag(), false);** 设置为false后，失败后消息就丢弃了。
![在这里插入图片描述](https://img-blog.csdnimg.cn/20201017154207917.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L3FxXzQxNzYyNTk0,size_16,color_FFFFFF,t_70#pic_center)
### 死信队列
到这儿消息的可靠性基本已经可以保证了，但是在consumer接收Queue中的消息时，如果有处理不掉的消息，会进行一个丢弃，如果想对丢弃的消息进行一个保存或者二次处理，那么就需要用到死信队列了。

**死信队列**也可以看做死信交换机，当消息在一个队列中变成死信后，它能被重新被发送到特定的交换器中，这个交换器就是DLX ，绑定DLX 的队列就称之为死信队列。消息变成死信一般是由于以下几种情况:

 1. 消息被拒绝，requeue 被设置为 false, 可通过上一介绍的 void basicReject (deliveryTag, requeue) 或 void basicNack(deliveryTag,multiple, requeue) 完成设置 ;
 2. 消息过期; 
 3. 队列超出最大长度。

死信队列也可以看做是对一个正常队列的绑定，我们需要先建立一个死信队列，然后再建立一个正常的队列，在正常队列构造函数的**Map<String, Object> arguments** 参数中设置**x-dead-letter-exchange** 和 **x-dead-letter-routing-key** 属性，与死信队列的交换机和routingKey绑定。

```java
    //死信队列生成
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
	//将死信队列的交换机和routingKey绑定到正常的队列上。
    @Bean
    public Queue orderQueue(){
        Map<String, Object> map = new HashMap<>();
        //声明当前死信的exchange
        map.put("x-dead-letter-exchange", DEAD_EXCHANGE);
        //声明当前死信的routingkey
        map.put("x-dead-letter-routing-key", DEAD_ROUTING_KEY);
        return new Queue(ORDER_QUEUE_NAME, true, false, false, map);
    }
```
启动一下项目，看一下rabbitmq服务器的变化。可以看到原来的队列多了**DLX**和**DLK** ， 两个标识，分别代表 DLX：x-dead-letter-exchange:绑定的死信队列交换机， DLK：x-dead-letter-routing-key:绑定的死信队列routingKey
![在这里插入图片描述](https://img-blog.csdnimg.cn/20201017160050833.png#pic_center)
最后和监听普通队列一样设置一个**SimpleMessageListenerContainer**即可监听死信队列的消息。那么在绑定队列如果产生了消息的丢失，就会发放到死信队列。
```java
    //监听的死信队列
    @Resource
    Queue queueDead;

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
```
将上述设置为消息丢弃后，启动项目，发送信息，查看结果。最后消费失败，到达死信队列。可以进行后续的流程的处理。
![在这里插入图片描述](https://img-blog.csdnimg.cn/20201017160607895.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L3FxXzQxNzYyNTk0,size_16,color_FFFFFF,t_70#pic_center)
## 总结
以上粗浅的过了一遍rabbitmq的消息可靠性保证，能够在大几率的情况下防止消息的丢失，如果出现一些极端情况，消息的丢失还是无法避免。

我们通过**入队列前**，**在队列中**，**出队列后**，三个阶段设置对消息的可靠性，尽量将消息设置在可控范围内。完整的代码已经上传至github。
