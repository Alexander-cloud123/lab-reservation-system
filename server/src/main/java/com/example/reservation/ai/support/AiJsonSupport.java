package com.example.reservation.ai.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 模型 JSON 输出容错解析（4 个 AI 接口的统一解析入口）
 *
 * 背景：AgnesClient 以 response_format={"type":"json_object"} 调用，而免费档模型遵从度有限，
 * 实际输出常偏离约定，已实测到的形态包括：
 *   - markdown 代码块包裹（```json ... ```）；
 *   - 前后夹带说明文字（"好的，以下是我的推荐：{...}"）；
 *   - 约定为顶层数组时只返回单个对象（如 {"classroomId":2,"reason":"..."}）。
 * 此前仅推荐接口做了容错，其余 3 个接口为裸 readTree，同类偏差会直接触发降级。
 *
 * 本类统一提供两种入口：
 *  1. {@link #readObject}：对象形态约定（解析 / 问答 / 合规），兼容代码块包裹、夹带文字、单元素数组包裹；
 *  2. {@link #extractArray}：数组形态约定（推荐），兼容顶层数组、单对象、常见包装键、首个非空数组字段。
 *
 * 解析失败一律返回 null，由调用方决定降级话术（不在本类内抛异常）。
 *
 * @author reservation-team
 */
@Slf4j
@Component
public class AiJsonSupport {

    /** 数组形态的常见包装键（模型可能自行换用键名） */
    private static final List<String> WRAPPER_KEYS =
            List.of("recommendations", "data", "items", "result", "list");

    /** 主 ObjectMapper（Spring 统一配置实例，避免各组件自行 new 造成配置分叉） */
    @Resource
    private ObjectMapper objectMapper;

    /**
     * 宽松读取对象根节点（供对象形态约定的接口使用）
     *
     * @return 对象节点；无法得到对象时返回 null（调用方据此触发降级）
     */
    public JsonNode readObject(String content) {
        JsonNode root = readTreeLenient(content);
        if (root == null) {
            return null;
        }
        if (root.isObject()) {
            return root;
        }
        // 容错：模型把对象包成单元素数组时取首个对象
        if (root.isArray() && !root.isEmpty() && root.get(0).isObject()) {
            return root.get(0);
        }
        return null;
    }

    /**
     * 提取数组节点（供数组形态约定的接口使用）
     * 依次尝试：顶层数组 → 单对象（包成单元素数组）→ 包装键 → 首个非空数组字段
     *
     * @return 数组节点；无法得到数组时返回 null（调用方据此触发降级）
     */
    public JsonNode extractArray(String content) {
        JsonNode root = readTreeLenient(content);
        if (root == null) {
            return null;
        }
        if (root.isArray()) {
            return root;
        }
        if (!root.isObject()) {
            return null;
        }
        // 形态：模型只给了单个对象
        if (root.hasNonNull("classroomId")) {
            return objectMapper.createArrayNode().add(root);
        }
        // 形态：包装键（含模型自行换用常见键名）
        for (String key : WRAPPER_KEYS) {
            JsonNode node = root.path(key);
            if (node.isArray() && !node.isEmpty()) {
                return node;
            }
            if (node.isObject() && node.hasNonNull("classroomId")) {
                return objectMapper.createArrayNode().add(node);
            }
        }
        // 兜底：取第一个非空数组字段
        for (JsonNode node : root) {
            if (node.isArray() && !node.isEmpty()) {
                return node;
            }
        }
        return null;
    }

    /**
     * 宽松读取 JSON 根节点：剥离 markdown 代码块后直接解析；
     * 失败则截取最外层 {...} / [...] 片段重试（容错模型夹带说明文字）
     */
    private JsonNode readTreeLenient(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String text = stripCodeFence(content);
        try {
            return objectMapper.readTree(text);
        } catch (Exception ignored) {
            // 落入下方片段截取重试
        }
        int start = -1;
        char open = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{' || c == '[') {
                start = i;
                open = c;
                break;
            }
        }
        if (start < 0) {
            return null;
        }
        int end = text.lastIndexOf(open == '{' ? '}' : ']');
        if (end <= start) {
            return null;
        }
        try {
            return objectMapper.readTree(text.substring(start, end + 1));
        } catch (Exception e) {
            log.warn("AI 模型输出 JSON 解析失败：{}", e.getMessage());
            return null;
        }
    }

    /** 剥离 markdown 代码块包裹（```json ... ```） */
    private String stripCodeFence(String text) {
        String t = text.trim();
        if (!t.startsWith("```")) {
            return t;
        }
        int firstLineEnd = t.indexOf('\n');
        int fenceEnd = t.lastIndexOf("```");
        if (firstLineEnd < 0 || fenceEnd <= firstLineEnd) {
            return t;
        }
        return t.substring(firstLineEnd + 1, fenceEnd).trim();
    }
}