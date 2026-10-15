package com.example.reservation.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * AI SSE 流式应答专用线程池（R12）
 *  - 仅承载 chat?stream=true 的异步编排，不与其他业务共享；
 *  - 有界队列 + AbortPolicy：占满时由 controller 捕获拒绝并回 error 事件，不压垮 Tomcat 请求线程；
 *  - daemon 线程：应用关闭时不阻塞 JVM 退出（ThreadPoolTaskExecutor 自身也随 Bean 销毁）。
 *
 * @author reservation-team
 */
@Configuration
public class AiExecutorConfig {

    @Bean(name = "aiStreamExecutor")
    public ThreadPoolTaskExecutor aiStreamExecutor(AiProperties aiProperties) {
        // 参数统一走 application.yml ai.stream.executor.*（缺省值与 R12 基线一致：2/8/100）
        AiProperties.Executor config = aiProperties.getStream().getExecutor();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(config.getCorePoolSize());
        executor.setMaxPoolSize(config.getMaxPoolSize());
        executor.setQueueCapacity(config.getQueueCapacity());
        executor.setThreadNamePrefix("ai-stream-");
        executor.setDaemon(true);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }
}
