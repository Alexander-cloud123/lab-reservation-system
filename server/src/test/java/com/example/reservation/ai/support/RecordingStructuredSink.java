package com.example.reservation.ai.support;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 结构化流式 sink 的录制假实现（R13-5 单测共用，不依赖 Servlet）。
 * 事件扁平为字符串便于精确断言顺序；result 保留最后一个 VO 供字段断言。
 */
public class RecordingStructuredSink<T> implements StructuredStreamSink<T> {

    /** 事件序列：meta(...) / phase(code,text) / result / done / error(...) */
    public final List<String> events = new ArrayList<>();

    /** 最后一个 result 载荷（正常每流恰好 1 个） */
    public T result;

    @Override
    public void meta(boolean enabled, boolean degraded, String message) {
        events.add("meta(" + enabled + "," + degraded + "," + message + ")");
    }

    @Override
    public void phase(String phase, String text) {
        events.add("phase(" + phase + "," + text + ")");
    }

    @Override
    public void result(T vo) {
        events.add("result");
        this.result = vo;
    }

    @Override
    public void done() {
        events.add("done");
    }

    @Override
    public void error(String message) {
        events.add("error(" + message + ")");
    }

    /** 构造一个在 phase 时抛 IOException 的 sink（模拟客户端在调用上游前断开） */
    public static <T> StructuredStreamSink<T> phaseAborting() {
        return new StructuredStreamSink<>() {
            @Override
            public void meta(boolean enabled, boolean degraded, String message) {
                // meta 已成功发出
            }

            @Override
            public void phase(String phase, String text) throws IOException {
                throw new IOException("client gone before phase");
            }

            @Override
            public void result(T vo) {
                throw new AssertionError("中断流不应再发 result");
            }

            @Override
            public void done() {
                throw new AssertionError("中断流不应再发 done");
            }

            @Override
            public void error(String message) {
                throw new AssertionError("中断流不应再发 error");
            }
        };
    }
}
