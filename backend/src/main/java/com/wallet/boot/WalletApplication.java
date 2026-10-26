package com.wallet.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.context.annotation.ImportResource;

/**
 * Spring Boot 실행 JAR 진입점.
 *
 * <p>업무 컴포넌트는 기존 XML component-scan이 계속 담당한다. Boot 스캔은 이 패키지로
 * 한정하고 WebMvcAutoConfiguration을 제외해 {@code mvc:annotation-driven}과 MVC 인프라가
 * 중복 등록되지 않도록 한다.</p>
 */
@SpringBootApplication(
    scanBasePackages = "com.wallet.boot",
    exclude = {
        WebMvcAutoConfiguration.class,
        TransactionAutoConfiguration.class
    }
)
@ImportResource({
    "classpath:/spring/root-context.xml",
    "classpath:/spring/servlet-context.xml"
})
public class WalletApplication {

    public static void main(String[] args) {
        SpringApplication.run(WalletApplication.class, args);
    }
}
