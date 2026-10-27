package com.yigoheojang.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import javax.xml.parsers.DocumentBuilderFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.util.ClassUtils;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.converter.ResourceRegionHttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperFactoryBean;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.support.AllEncompassingFormHttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.wallet.boot.WalletApplication;

@SpringBootTest(classes = WalletApplication.class)
class JavaConfigurationContractTest {
    @Autowired
    private ConfigurableApplicationContext context;

    @Test
    void beanInventory() throws Exception {
        var factory = context.getBeanFactory();
        var inventory = Arrays.stream(factory.getBeanDefinitionNames()).sorted().map(name -> {
            var definition = factory.getBeanDefinition(name);
            Class<?> type = context.getType(name);
            String typeName = type == null ? "unknown" : ClassUtils.getUserClass(type).getName()
                .replaceAll("\\$\\$Lambda/0x[0-9a-f]+", java.util.regex.Matcher.quoteReplacement("$$Lambda"));
            return name + "|" + typeName + "|"
                + (definition.isSingleton() ? "singleton" : definition.getScope()) + "|"
                + String.join(",", context.getAliases(name));
        }).toList();
        Files.createDirectories(Path.of("target", "configuration-migration"));
        Files.write(Path.of("target", "configuration-migration", "beans.txt"), inventory,
            StandardCharsets.UTF_8);

        List<String> before;
        try (var reader = new BufferedReader(new InputStreamReader(
            new ClassPathResource("configuration/beans-before.txt").getInputStream(), StandardCharsets.UTF_8))) {
            before = reader.lines().toList();
        }
        var beforeNames = new TreeSet<String>();
        var afterNames = new TreeSet<String>();
        before.forEach(line -> beforeNames.add(line.split("\\|", -1)[0]));
        inventory.forEach(line -> afterNames.add(line.split("\\|", -1)[0]));
        var diff = new java.util.ArrayList<String>();
        beforeNames.stream().filter(name -> !afterNames.contains(name)).forEach(name -> diff.add("- " + name));
        afterNames.stream().filter(name -> !beforeNames.contains(name)).forEach(name -> diff.add("+ " + name));
        Files.write(Path.of("target", "configuration-migration", "bean-names.diff"), diff,
            StandardCharsets.UTF_8);
        assertThat(diff).containsExactlyElementsOf(readFixture("configuration/bean-names.diff"));
        assertThat(inventory).containsExactlyElementsOf(readFixture("configuration/beans-after.txt"));

        // 모든 업무 컴포넌트의 이름, 타입, 스코프와 별칭이 유지되는지 확인한다.
        before.stream().filter(line -> line.contains("|com.wallet."))
            .forEach(line -> assertThat(inventory).contains(line));
        for (String name : List.of("dataSource", "sqlSessionFactory", "transactionManager",
            "passwordEncoder", "objectMapper", "mailSender", "jedisPoolConfig", "jedisPool",
            "notificationTaskScheduler", "chatbotRequestFactory", "restTemplate", "mvcCorsConfigurations",
            "org.mybatis.spring.mapper.MapperScannerConfigurer#0",
            "org.springframework.context.support.PropertySourcesPlaceholderConfigurer#0")) {
            assertThat(inventory).contains(before.stream().filter(line -> line.startsWith(name + "|"))
                .findFirst().orElseThrow());
        }
        assertThat(context.getBeansOfType(PropertySourcesPlaceholderConfigurer.class)).hasSize(1);
        assertThat(context.getBeanDefinitionNames()).noneMatch(name -> name.contains("testProbe"));
    }

    private List<String> readFixture(String path) throws Exception {
        try (var reader = new BufferedReader(new InputStreamReader(
            new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().toList();
        }
    }

    @Test
    void dataAccessAndPublicTermsEndpointUseTheMigratedInfrastructure() throws Exception {
        var source = context.getBean("dataSource", HikariDataSource.class);
        assertThat(context.getBean(DataSourceTransactionManager.class).getDataSource()).isSameAs(source);
        assertThat(new JdbcTemplate(source).queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        MockMvcBuilders.webAppContextSetup((WebApplicationContext) context).build()
            .perform(get("/api/terms")).andExpect(status().isOk());
    }

    @Test
    void all32MapperResourcesAreLoadedAndMappedToTheirInterfaces() throws Exception {
        assertThat(context.getBean("&sqlSessionFactory")).isInstanceOf(SqlSessionFactoryBean.class);
        var configuration = context.getBean(SqlSessionFactory.class).getConfiguration();
        assertThat(configuration.isMapUnderscoreToCamelCase()).isTrue();
        var resources = new PathMatchingResourcePatternResolver().getResources("classpath*:mappers/**/*.xml");
        assertThat(resources).hasSize(32);
        var parser = DocumentBuilderFactory.newInstance();
        parser.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        parser.setFeature("http://xml.org/sax/features/external-general-entities", false);
        parser.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        for (var resource : resources) {
            assertThat(configuration.isResourceLoaded(resource.toString())).as(resource.toString()).isTrue();
            try (var input = resource.getInputStream()) {
                var document = parser.newDocumentBuilder().parse(input);
                var mapper = Class.forName(document.getDocumentElement().getAttribute("namespace"));
                assertThat(configuration.hasMapper(mapper)).as(mapper.getName()).isTrue();
                assertThat(context.getBeansOfType(mapper)).hasSize(1);
            }
        }
    }

    @Test
    void mvcUsesTheOriginalConverterOrderMapperAndPathMatcher() {
        var mapper = context.getBean("objectMapper", ObjectMapper.class);
        assertThat(context.getBean("&objectMapper")).isInstanceOf(Jackson2ObjectMapperFactoryBean.class);
        assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isFalse();
        assertThat(mapper.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)).isTrue();
        var converters = context.getBean(RequestMappingHandlerAdapter.class).getMessageConverters();
        assertThat(converters).extracting(Object::getClass).containsExactly(
            ByteArrayHttpMessageConverter.class, StringHttpMessageConverter.class,
            ResourceHttpMessageConverter.class, ResourceRegionHttpMessageConverter.class,
            AllEncompassingFormHttpMessageConverter.class, MappingJackson2HttpMessageConverter.class);
        assertThat(((StringHttpMessageConverter) converters.get(1)).getDefaultCharset())
            .isEqualTo(StandardCharsets.UTF_8);
        assertThat(((MappingJackson2HttpMessageConverter) converters.get(5)).getObjectMapper()).isSameAs(mapper);
        var mapping = context.getBean(RequestMappingHandlerMapping.class);
        assertThat(mapping.getPatternParser()).isNull();
        assertThat(mapping.getPathMatcher()).isSameAs(context.getBean("antPathMatcher"));
        assertThat(context.getBean("mvcPathMatcher")).isSameAs(context.getBean("antPathMatcher"));
        assertThat(context.containsBean("defaultValidator")).isFalse();
        assertThat(context.getBean("mvcValidator"))
            .isSameAs(context.getBean("org.springframework.validation.beanvalidation.OptionalValidatorFactoryBean#0"));
    }

    @Test
    void schedulerAndTransactionInterceptionKeepTheirOriginalContracts() throws Exception {
        var scheduler = context.getBean("notificationTaskScheduler", ThreadPoolTaskScheduler.class);
        assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(4);
        var processor = context.getBean(ScheduledAnnotationBeanPostProcessor.class);
        assertThat(processor.getScheduledTasks()).hasSize(2);
        var registrar = (ScheduledTaskRegistrar) ReflectionTestUtils.getField(processor, "registrar");
        assertThat(registrar.getScheduler()).isSameAs(scheduler);
        var attributes = context.getBean(AnnotationTransactionAttributeSource.class);
        assertThat(attributes.getTransactionAttribute(TransactionProbe.class.getMethod("publicMethod"),
            TransactionProbe.class)).isNotNull();
        assertThat(attributes.getTransactionAttribute(TransactionProbe.class.getDeclaredMethod("protectedMethod"),
            TransactionProbe.class)).isNull();
    }

    static class TransactionProbe {
        @Transactional
        public void publicMethod() { }

        @Transactional
        protected void protectedMethod() { }
    }
}
