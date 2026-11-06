package com.yigoheojang.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import javax.xml.parsers.DocumentBuilderFactory;

import com.zaxxer.hikari.HikariDataSource;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.validation.autoconfigure.ValidatorAdapter;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.converter.ResourceRegionHttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.converter.support.AllEncompassingFormHttpMessageConverter;
import org.springframework.http.converter.xml.Jaxb2RootElementHttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.beanvalidation.OptionalValidatorFactoryBean;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.FormContentFilter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.wallet.boot.WalletApplication;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = WalletApplication.class)
class JavaConfigurationContractTest {
    @Autowired
    private ConfigurableApplicationContext context;

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
    void mvcUsesTheAutoConfiguredJsonMapperAndKeepsTheJsonContract() throws Exception {
        var mapper = context.getBean(JsonMapper.class);
        assertThat(context.getBeansOfType(JsonMapper.class)).hasSize(1);
        assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isFalse();
        assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)).isFalse();
        assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)).isFalse();
        assertThat(mapper.isEnabled(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)).isTrue();
        assertThat(mapper.isEnabled(DateTimeFeature.ONE_BASED_MONTHS)).isTrue();
        assertThat(mapper.isEnabled(EnumFeature.READ_ENUMS_USING_TO_STRING)).isFalse();
        assertThat(mapper.isEnabled(EnumFeature.WRITE_ENUMS_USING_TO_STRING)).isFalse();
        assertThat(mapper.isEnabled(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)).isFalse();
        assertThat(mapper.isEnabled(MapperFeature.DEFAULT_VIEW_INCLUSION)).isFalse();
        var converters = context.getBean(RequestMappingHandlerAdapter.class).getMessageConverters();
        assertThat(converters).<Class<?>>extracting(Object::getClass).containsExactly(
            ByteArrayHttpMessageConverter.class, StringHttpMessageConverter.class,
            ResourceHttpMessageConverter.class, ResourceRegionHttpMessageConverter.class,
            AllEncompassingFormHttpMessageConverter.class, JacksonJsonHttpMessageConverter.class,
            Jaxb2RootElementHttpMessageConverter.class);
        assertThat(((StringHttpMessageConverter) converters.get(1)).getDefaultCharset())
            .isEqualTo(StandardCharsets.UTF_8);
        assertThat(((JacksonJsonHttpMessageConverter) converters.get(5)).getMapper()).isSameAs(mapper);
        var mapping = context.getBean(RequestMappingHandlerMapping.class);
        assertThat(mapping.getPatternParser()).isNotNull();
        assertThat(context.getBeansOfType(FormContentFilter.class)).isEmpty();
        assertThat(context.containsBean("defaultValidator")).isFalse();
        var adapter = context.getBean("mvcValidator", ValidatorAdapter.class);
        assertThat(adapter.getTarget())
            .isSameAs(context.getBean(OptionalValidatorFactoryBean.class));

        var result = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context).build()
            .perform(get("/test-only/not-found"))
            .andExpect(status().isInternalServerError())
            .andReturn();
        assertThat(result.getResolvedException()).isInstanceOf(NoResourceFoundException.class);
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
