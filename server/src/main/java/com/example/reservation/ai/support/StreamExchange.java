package com.example.reservation.ai.support;

/**
 * 上游流式 HTTP 交换结果（R13-4）。
 *
 * <p>由 {@link StreamTransport} 的同步桥接实现返回，调用方（AgnesClient）据此映射为降级/中断/成功，
 * 与具体响应式技术细节（Reactor / JDK HttpClient）解耦——单测可以用 fake transport 覆盖编排分支。
 *
 * @param state      交换终态
 * @param statusCode 上游 HTTP 状态码（仅 {@link State#HTTP_ERROR} 时有意义，其余为 0）
 */
public record StreamExchange(State state, int statusCode) {

    /** 交换终态：正常读完 / 上游 HTTP 错误 / 下游主动取消 / 传输层异常（含帧间超时） */
    public enum State {
        COMPLETED, HTTP_ERROR, CANCELLED, TRANSPORT_ERROR
    }

    /** 2xx 且流正常结束 */
    public static StreamExchange completed() {
        return new StreamExchange(State.COMPLETED, 0);
    }

    /** 上游返回 4xx/5xx（未向下游回调任何帧） */
    public static StreamExchange httpError(int statusCode) {
        return new StreamExchange(State.HTTP_ERROR, statusCode);
    }

    /**
     * 下游（SseEmitter）写入失败导致取消：非上游故障，调用方不记降级指标、静默结束。
     */
    public static StreamExchange cancelled() {
        return new StreamExchange(State.CANCELLED, 0);
    }

    /** 连接失败 / 帧间超时 / 其他传输异常 */
    public static StreamExchange transportError() {
        return new StreamExchange(State.TRANSPORT_ERROR, 0);
    }
}
