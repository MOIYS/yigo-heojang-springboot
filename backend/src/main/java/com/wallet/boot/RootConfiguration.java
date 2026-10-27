package com.wallet.boot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Properties;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.mapper.MapperScannerConfigurer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Role;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.aop.config.AopConfigUtils;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.Jackson2ObjectMapperFactoryBean;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.web.client.RestTemplate;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/** 기존 루트 컨텍스트의 동작 계약을 유지하는 인프라 설정과 서비스 컴포넌트 스캔. */
@Configuration
@ComponentScan(basePackages = "com.wallet", excludeFilters = {
    @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = Controller.class),
    @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.wallet\\.boot\\..*")
})
@EnableScheduling
@EnableAsync
@Import(RootConfiguration.TransactionProxyRegistrar.class)
public class RootConfiguration implements SchedulingConfigurer {

    /** 외부에서 주입한 시크릿을 포함한 Environment 설정이 로컬 속성 파일보다 우선하도록 유지한다. */
    @Bean("org.springframework.context.support.PropertySourcesPlaceholderConfigurer#0")
    public static PropertySourcesPlaceholderConfigurer propertyPlaceholderConfigurer() throws IOException {
        var resolver = new PathMatchingResourcePatternResolver();
        var resources = new ArrayList<Resource>();
        for (String location : new String[] {
            "classpath:db.properties", "classpath:redis.properties", "classpath*:jwt.properties"
        }) {
            resources.addAll(java.util.List.of(resolver.getResources(location)));
        }
        var configurer = new PropertySourcesPlaceholderConfigurer();
        configurer.setLocations(resources.toArray(Resource[]::new));
        return configurer;
    }

    @Bean(destroyMethod = "close")
    public HikariDataSource dataSource(
        @Value("${db.driver}") String driver,
        @Value("${db.url}") String url,
        @Value("${db.username}") String username,
        @Value("${db.password}") String password
    ) {
        var source = new HikariDataSource();
        source.setDriverClassName(driver);
        source.setJdbcUrl(url);
        source.setUsername(username);
        source.setPassword(password);
        return source;
    }

    @Bean
    public SqlSessionFactoryBean sqlSessionFactory(DataSource dataSource) throws IOException {
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver()
            .getResources("classpath*:mappers/**/*.xml"));
        return factory;
    }

    @Bean(name = {"org.mybatis.spring.mapper.MapperScannerConfigurer#0",
        "org.mybatis.spring.mapper.MapperScannerConfigurer"})
    public static MapperScannerConfigurer mapperScannerConfigurer() {
        var scanner = new MapperScannerConfigurer();
        scanner.setBasePackage("com.wallet");
        scanner.setAnnotationClass(Mapper.class);
        scanner.setSqlSessionFactoryBeanName("sqlSessionFactory");
        return scanner;
    }

    @Bean
    public DataSourceTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    // 인프라를 직접 구성해 public 메서드만 가로채는 기존 동작과 Bean 이름을 유지한다.
    // Spring 6의 EnableTransactionManagement는 protected 및 패키지 접근 메서드도 가로챈다.
    public static class TransactionProxyRegistrar implements ImportBeanDefinitionRegistrar {
        @Override
        public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
            AopConfigUtils.registerAutoProxyCreatorIfNecessary(registry);
        }
    }

    @Bean("org.springframework.transaction.annotation.AnnotationTransactionAttributeSource#0")
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public AnnotationTransactionAttributeSource transactionAttributeSource() {
        return new AnnotationTransactionAttributeSource(true);
    }

    @Bean("org.springframework.transaction.interceptor.TransactionInterceptor#0")
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public TransactionInterceptor transactionInterceptor() {
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManagerBeanName("transactionManager");
        interceptor.setTransactionAttributeSource(transactionAttributeSource());
        return interceptor;
    }

    @Bean("org.springframework.transaction.config.internalTransactionAdvisor")
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public BeanFactoryTransactionAttributeSourceAdvisor transactionAdvisor() {
        var advisor = new BeanFactoryTransactionAttributeSourceAdvisor();
        advisor.setTransactionAttributeSource(transactionAttributeSource());
        advisor.setAdviceBeanName("org.springframework.transaction.interceptor.TransactionInterceptor#0");
        return advisor;
    }

    @Bean("org.springframework.transaction.config.internalTransactionalEventListenerFactory")
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static TransactionalEventListenerFactory transactionalEventListenerFactory() {
        return new TransactionalEventListenerFactory();
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public Jackson2ObjectMapperFactoryBean objectMapper() {
        return new Jackson2ObjectMapperFactoryBean();
    }

    @Bean
    public JavaMailSenderImpl mailSender(
        @Value("${mail.host}") String host,
        @Value("${mail.port}") int port,
        @Value("${mail.username}") String username,
        @Value("${mail.password}") String password,
        @Value("${mail.smtp.auth}") String auth,
        @Value("${mail.smtp.starttls.enable}") String starttls,
        @Value("${mail.smtp.connectiontimeout}") String connectionTimeout,
        @Value("${mail.smtp.timeout}") String timeout,
        @Value("${mail.smtp.writetimeout}") String writeTimeout,
        @Value("${mail.debug}") String debug
    ) {
        var sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setUsername(username);
        sender.setPassword(password);
        sender.setDefaultEncoding("UTF-8");
        var properties = new Properties();
        properties.setProperty("mail.smtp.auth", auth);
        properties.setProperty("mail.smtp.starttls.enable", starttls);
        properties.setProperty("mail.smtp.connectiontimeout", connectionTimeout);
        properties.setProperty("mail.smtp.timeout", timeout);
        properties.setProperty("mail.smtp.writetimeout", writeTimeout);
        properties.setProperty("mail.debug", debug);
        sender.setJavaMailProperties(properties);
        return sender;
    }

    @Bean
    public JedisPoolConfig jedisPoolConfig(
        @Value("${redis.pool.max-total}") int maxTotal,
        @Value("${redis.pool.max-idle}") int maxIdle,
        @Value("${redis.pool.min-idle}") int minIdle
    ) {
        var config = new JedisPoolConfig();
        config.setMaxTotal(maxTotal);
        config.setMaxIdle(maxIdle);
        config.setMinIdle(minIdle);
        return config;
    }

    @Bean(destroyMethod = "close")
    public JedisPool jedisPool(
        JedisPoolConfig jedisPoolConfig,
        @Value("${redis.host}") String host,
        @Value("${redis.port}") int port,
        @Value("${redis.timeout-millis}") int timeout,
        @Value("${redis.password}") String password
    ) {
        return new JedisPool(jedisPoolConfig, host, port, timeout, password);
    }

    @Bean
    public ThreadPoolTaskScheduler notificationTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        return scheduler;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(notificationTaskScheduler());
    }

    @Bean
    public SimpleClientHttpRequestFactory chatbotRequestFactory() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(30000);
        return factory;
    }

    @Bean
    public RestTemplate restTemplate(SimpleClientHttpRequestFactory chatbotRequestFactory) {
        return new RestTemplate(chatbotRequestFactory);
    }
}
