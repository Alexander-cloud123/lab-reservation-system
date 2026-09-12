package com.example.reservation.common;

import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册鉴权拦截器 + 跨域支持
 *
 * @author reservation-team
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Resource
    private AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 除登录/注册外，/api/** 全部走鉴权拦截器
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/user/login", "/api/user/register");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 前端开发服务器（Vite 5173）与跨源调用放行
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
