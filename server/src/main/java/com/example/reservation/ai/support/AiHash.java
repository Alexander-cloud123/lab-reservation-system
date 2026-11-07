package com.example.reservation.ai.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * AI 模块哈希工具（单一实现，避免各处重复写摘要逻辑）
 *
 * 用途：为 Prompt 版本号与缓存指纹生成完整 SHA-256 十六进制摘要。
 * 缓存隔离不能依赖 32 bit 短哈希：已知不同用户输入可碰撞并读到彼此的个人答案。
 *
 * @author hyr
 */
public final class AiHash {

    private AiHash() {
    }

    /**
     * 计算内容的完整 SHA-256 十六进制摘要。
     *
     * @param raw 原始内容（空白视为无内容）
     * @return 64 位十六进制字符串；空白输入返回 none，算法不可用返回 unknown
     */
    public static String sha256(String raw) {
        if (raw == null || raw.isBlank()) {
            return "none";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // JDK 必然内置 SHA-256，此分支仅为编译期检查兜底，不抛异常以免影响主流程
            return "unknown";
        }
    }

    /**
     * 历史方法名保留以兼容既有调用方；安全性修复后同样返回完整摘要，不再截断。
     * 旧短哈希缓存 Key 因而自然失效，不会继续命中可能碰撞的个人答案。
     */
    @Deprecated(forRemoval = false)
    public static String sha256Prefix8(String raw) {
        return sha256(raw);
    }
}