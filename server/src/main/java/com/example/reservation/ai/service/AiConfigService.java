package com.example.reservation.ai.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.reservation.ai.config.AiConstants;
import com.example.reservation.ai.config.AiProperties;
import com.example.reservation.entity.AiConfig;
import com.example.reservation.mapper.AiConfigMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * AI 配置读取服务（R7，只读 ai_config 表，不改表结构、不改基线值）
 *  - 配置键：ai_enable / ai_model / compliance_keywords / prompt_parse / prompt_compliance
 *  - 双开关口径：有效开关 = application.yml ai.enable（静态）AND ai_config.ai_enable（动态）
 *    （R7 提示词依据：ai.enable=false 默认关闭；ai_config.ai_enable 可动态启停，便于演示与测试）
 *
 * @author reservation-team
 */
@Service
public class AiConfigService {

    @Resource
    private AiConfigMapper aiConfigMapper;

    @Resource
    private AiProperties aiProperties;

    /**
     * 按配置键读取配置值（ai_config.config_value），不存在返回 null
     */
    public String getValue(String configKey) {
        AiConfig config = aiConfigMapper.selectOne(
                new LambdaQueryWrapper<AiConfig>().eq(AiConfig::getConfigKey, configKey).last("LIMIT 1"));
        return config == null ? null : config.getConfigValue();
    }

    /**
     * AI 有效开关（双开关）：yml ai.enable 与 ai_config.ai_enable 均为 true 时启用
     */
    public boolean isAiEnabled() {
        return aiProperties.isEnable() && isDbEnable();
    }

    /**
     * 数据库动态开关：ai_config.ai_enable（缺省按关闭处理，保证默认不启用）
     */
    public boolean isDbEnable() {
        String value = getValue(AiConstants.CONFIG_KEY_AI_ENABLE);
        return value != null && Boolean.parseBoolean(value.trim());
    }

    /**
     * 生效模型：ai_config.ai_model 优先，缺省回退 application.yml ai.model
     */
    public String getEffectiveModel() {
        String dbModel = getValue(AiConstants.CONFIG_KEY_AI_MODEL);
        return StrUtil.isNotBlank(dbModel) ? dbModel.trim() : aiProperties.getModel();
    }

    /**
     * 合规校验本地违规关键词列表（compliance_keywords，兼容中英文逗号/顿号分隔，去空）
     */
    public List<String> getComplianceKeywordList() {
        String raw = getValue(AiConstants.CONFIG_KEY_COMPLIANCE_KEYWORDS);
        if (StrUtil.isBlank(raw)) {
            return List.of();
        }
        return Arrays.stream(raw.split("[,，、;；]"))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .toList();
    }

    /**
     * 自然语言预约解析 Prompt 模板（ai_config.prompt_parse）
     */
    public String getPromptParse() {
        return getValue(AiConstants.CONFIG_KEY_PROMPT_PARSE);
    }

    /**
     * 预约合规校验 Prompt 模板（ai_config.prompt_compliance）
     */
    public String getPromptCompliance() {
        return getValue(AiConstants.CONFIG_KEY_PROMPT_COMPLIANCE);
    }
}
