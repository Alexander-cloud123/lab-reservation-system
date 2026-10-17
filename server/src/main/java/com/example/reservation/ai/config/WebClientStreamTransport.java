package com.example.reservation.ai.config;

import com.example.reservation.ai.support.StreamExchange;
import com.example.reservation.ai.support.StreamTransport;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * {@link StreamTransport} 的生产实现（R13-4）：WebClient 响应式消费上游 SSE。
 *
 * <p>落地姿势（R13 章程裁决 2）：
 * <ul>
 *   <li>传输层用 {@link JdkClientHttpConnector}（JDK 21 自带 {@link HttpClient}），
 *       <b>不引入 reactor-netty/Netty 客户端</b>，服务端栈保持 Spring MVC + Tomcat；</li>
 *   <li>{@code bodyToFlux(String)} 由 Spring 自动完成 SSE 分帧（[DONE] 非 JSON，故帧体取 String，
 *       JSON 解析交 AgnesClient 侧纯函数）；</li>
 *   <li>{@code Flux.timeout} 实现帧间超时（首帧/相邻帧间隔），语义对齐旧 RestClient 读取超时；</li>
 *   <li>订阅在 JDK HttpClient 线程回调，本方法在 aiStreamExecutor 线程以 CountDownLatch 桥接回同步；</li>
 *   <li>下游 SseEmitter 写入失败（UncheckedIOException）→ {@code dispose()} 取消上游订阅，
 *       JDK HttpClient 随即释放上游连接（比旧版靠关闭 InputStream 中断阻塞读更及时）。</li>
 * </ul>
 */
@Slf4j
@Component
public class WebClientStreamTransport implements StreamTransport {

    /** 上游连接超时固定 10s（与非流式 RestClient 口径一致，网络不可达快速失败） */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** 桥接等待兜底：帧间超时之外再留关闭缓冲；正常流由 complete/error 先行放行，永不触发 */
    private static final Duration AWAIT_GRACE = Duration.ofSeconds(30);

    private final WebClient webClient;

    public WebClientStreamTransport() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        this.webClient = WebClient.builder()
                .clientConnector(new JdkClientHttpConnector(httpClient))
                .build();
    }

    @Override
    public StreamExchange postStream(String url, String bearerToken, JsonNode body,
                                     Duration frameTimeout,
                                     Runnable onReady, Consumer<String> onFrame) {
        AtomicInteger statusRef = new AtomicInteger(0);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        // exchangeToFlux：在收到响应头时判定状态；2xx 才回调 onReady 并展开 SSE 帧流
        Flux<String> frameFlux = webClient.post()
                .uri(url)
                .header("Authorization", "Bearer " + bearerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .exchangeToFlux(response -> {
                    if (response.statusCode().isError()) {
                        statusRef.set(response.statusCode().value());
                        return Flux.error(new UpstreamHttpStatusException(response.statusCode().value()));
                    }
                    onReady.run();
                    return response.bodyToFlux(String.class);
                })
                // 帧间超时：相邻 data 帧（含首帧）间隔上限；上游挂死时按传输异常降级，不占死线程
                .timeout(frameTimeout);

        Disposable subscription = frameFlux.subscribe(
                frame -> {
                    try {
                        onFrame.accept(frame);
                    } catch (UncheckedIOException e) {
                        // 中断契约：下游（SseEmitter）写失败 = 浏览器断开，立即取消上游并释放连接
                        cancelled.set(true);
                        errorRef.compareAndSet(null, e);
                        Disposable active = subscriptionRef.get();
                        if (active != null) {
                            active.dispose();
                        }
                        done.countDown();
                    }
                },
                err -> {
                    errorRef.compareAndSet(null, err);
                    // onReady 抛 UncheckedIOException 时错误在此到达（exchangeToFlux lambda 内），同属下游取消
                    if (err instanceof UncheckedIOException) {
                        cancelled.set(true);
                    }
                    done.countDown();
                },
                done::countDown);
        subscriptionRef.set(subscription);

        try {
            boolean finished = done.await(frameTimeout.plus(AWAIT_GRACE).toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                // 纯兜底：帧间超时已应先触发；理论不可达，防 reactor/JDK 异常时序导致桥接线程永久挂住
                log.warn("上游流式桥接等待兜底超时，主动取消订阅：{}", url);
                subscription.dispose();
                return StreamExchange.transportError();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            subscription.dispose();
            return StreamExchange.cancelled();
        }

        if (cancelled.get()) {
            return StreamExchange.cancelled();
        }
        if (statusRef.get() != 0) {
            return StreamExchange.httpError(statusRef.get());
        }
        if (errorRef.get() != null) {
            // 连接失败 / 帧间超时 / 解码异常等：不向 AgnesClient 泄露 reactor 类型，统一记传输错误
            if (!(errorRef.get() instanceof UpstreamHttpStatusException)) {
                log.warn("上游流式传输异常：{}", errorRef.get().getMessage());
            }
            return StreamExchange.transportError();
        }
        return StreamExchange.completed();
    }

    /** 上游 4xx/5xx 标记异常（仅用于在响应式链路里携带状态码，不外抛到 ai 包之外） */
    private static class UpstreamHttpStatusException extends RuntimeException {
        private final int statusCode;

        UpstreamHttpStatusException(int statusCode) {
            super("upstream http " + statusCode);
            this.statusCode = statusCode;
        }

        int statusCode() {
            return statusCode;
        }
    }
}
