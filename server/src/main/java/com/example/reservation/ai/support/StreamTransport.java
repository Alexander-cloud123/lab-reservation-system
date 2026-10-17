package com.example.reservation.ai.support;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * 上游 SSE 流式传输端口（R13-4）。
 *
 * <p>薄适配层：只负责「发起 POST → SSE 自动分帧 → 逐帧回调 → 取消释放连接」，
 * 不含任何 Agnes 业务语义（鉴权话术、429 映射、JSON 帧解析均在 AgnesClient 侧）。
 * 生产实现 {@code WebClientStreamTransport}（WebClient + JDK HttpClient，不引 Netty）；
 * 单测用 fake 实现覆盖 AgnesClient 编排，不为 HTTP 客户端本身引 MockWebServer/WireMock。
 *
 * <p>同步桥接契约：方法在调用线程阻塞至流结束/取消/异常后才返回
 * （运行于 aiStreamExecutor 独立线程池），与 R12 的 RestClient.exchange 阻塞口径一致，
 * 调用方编排无需感知 Reactor。
 */
public interface StreamTransport {

    /**
     * 发起 SSE POST 并在当前线程桥接消费完整条流。
     *
     * @param url          完整上游地址
     * @param bearerToken  Bearer 密钥（调用方保证非空）
     * @param body         请求体（JSON）
     * @param frameTimeout 帧间超时：相邻两个 data 帧（含首帧）到达间隔上限，超时按传输异常返回
     * @param onReady      收到 2xx 响应头、即将开始读流时回调恰好一次；抛出
     *                     {@link java.io.UncheckedIOException} 视为下游取消
     * @param onFrame      每个 SSE data 帧的原始 payload（已由传输层完成 SSE 分帧）；
     *                     抛出 {@link java.io.UncheckedIOException} 时立即取消上游订阅并返回
     *                     {@link StreamExchange#cancelled()}
     * @return 交换终态
     */
    StreamExchange postStream(String url, String bearerToken, JsonNode body,
                              Duration frameTimeout,
                              Runnable onReady, Consumer<String> onFrame);
}
