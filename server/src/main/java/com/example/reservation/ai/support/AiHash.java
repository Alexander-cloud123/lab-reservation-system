package com.example.reservation.ai.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * AI 模块哈希工具（单一实现，避免各处重复写摘要逻辑）
 *
 * 用途：为 Prompt 版本号与缓存指纹生成短哈希。取 SHA-256 前 8 位十六进制——
 * 仅用于「内容一变、标识即变」的比对与拼 Key，不承担安全用途，故无需全量摘要。
 *
 * @author reservation-team
 */
public final class AiHash {

    private AiHash() {
    }

    /**
     * 计算内容的 SHA-256 前 8 位十六进制短哈希
     *
     * @param raw 原始内容（空白视为无内容）
     * @return 8 位十六进制字符串；空白输入返回 none，算法不可用返回 unknown
     */
    public static String sha256Prefix8(String raw) {
        if (raw == null || raw.isBlank()) {
            return "none";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 必然内置 SHA-256，此分支仅为编译期检查兜底，不抛异常以免影响主流程
            return "unknown";
        }
    }
}