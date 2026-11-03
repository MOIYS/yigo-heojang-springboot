package com.wallet.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;

/**
 * Spring Boot 실행 JAR 진입점.
 *
 * <p>Boot 스캔은 이 패키지로 한정한다. 업무 컴포넌트는 RootConfiguration과
 * WebMvcConfiguration에서 기존 범위로 스캔하며 MVC와 트랜잭션을 직접 구성한다.</p>
 */
@SpringBootApplication(
    scanBasePackages = "com.wallet.boot",
    exclude = {
        WebMvcAutoConfiguration.class,
        TransactionAutoConfiguration.class
    }
)
public class WalletApplication {

    public static void main(String[] args) {
        SpringApplication.run(WalletApplication.class, args);
    }
}
