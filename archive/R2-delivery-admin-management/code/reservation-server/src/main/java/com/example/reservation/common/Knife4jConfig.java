package com.example.reservation.common;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knife4j / OpenAPI 3 文档配置
 *
 * @author reservation-team
 */
@Configuration
public class Knife4jConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI().info(new Info()
                .title("教室预约系统接口文档")
                .description("高校实验室预约管理系统 - 后端接口文档（基于双重校验机制）")
                .version("3.1")
                .contact(new Contact().name("reservation-team")));
    }
}
