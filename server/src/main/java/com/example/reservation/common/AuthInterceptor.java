package com.example.reservation.common;

import cn.hutool.core.util.StrUtil;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTHeader;
import cn.hutool.jwt.JWTUtil;
import cn.hutool.jwt.signers.JWTSignerUtil;
import com.example.reservation.config.RedisCache;
import com.example.reservation.entity.SysUser;
import com.example.reservation.mapper.SysUserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 登录鉴权拦截器
 * 规则（spec.md 2.5）：
 *  1. 除登录/注册外，/api/** 全部要求携带合法 Token，否则返回 HTTP 401 + Result{code:401}
 *  2. 管理员专属接口前缀（/manage、/stats 等；R7 起 /api/ai 不再要求管理员角色）额外校验角色，学生 Token 访问返回 403
 *  3. 通过后写入 UserContext 供业务层获取当前用户
 *
 * @author hyr
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Value("${app.jwt.secret}")
    private String secret;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private RedisCache redisCache;

    /** 主 ObjectMapper（Spring 统一配置实例，避免各组件自行 new 造成配置分叉） */
    @Resource
    private ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 放行 CORS 预检请求
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String authHeader = request.getHeader(Constants.TOKEN_HEADER);
        if (StrUtil.isBlank(authHeader) || !authHeader.startsWith(Constants.TOKEN_PREFIX)) {
            return reject(response, ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMessage());
        }

        String token = authHeader.substring(Constants.TOKEN_PREFIX.length());
        Map<String, Object> claims;
        try {
            claims = parseAndVerify(token);
        } catch (BusinessException e) {
            return reject(response, e.getCode(), e.getMessage());
        } catch (Exception e) {
            // Token 解析/签名校验任何异常统一按未授权处理（不泄露 500 细节）
            return reject(response, ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMessage());
        }

        Long userId = Long.valueOf(String.valueOf(claims.get("userId")));
        String username = String.valueOf(claims.get("username"));
        Integer role = Integer.valueOf(String.valueOf(claims.get("role")));

        // 管理员专属接口：校验角色
        if (isAdminPath(request.getRequestURI()) && role != Constants.ROLE_ADMIN) {
            return reject(response, ResultCode.FORBIDDEN.getCode(), ResultCode.FORBIDDEN.getMessage());
        }

        // 账号状态 + Token 版本校验：账号被禁用/删除后已签发 Token 立即失效；
        // 登出/改密/管理员重置会把 sys_user.token_version 自增，旧 Token 因版本不匹配失效——
        // 该撤销凭据在数据库里，Redis 关闭、进程重启、多实例部署下同样成立。
        // M10 优化：状态先读 Redis 短缓存（60s TTL，值含 status 与 tokenVersion），未命中才回源查库并回填，
        // 消除"每请求一次 selectById"的数据库单点开销；状态/版本变更时主动删缓存——删除成功即即时生效，
        // 删除失败（Redis 不可用/超时）时旧快照最多残留一个 TTL（≤60s），该窗口内撤销后的旧 Token 仍可能
        // 被接受；窗口结束后快照过期、回源数据库比对即 401（详见 docs/Redis加分项接入说明.md §4「已知边界」）。
        RedisCache.UserState userState = redisCache.getUserState(userId);
        if (userState == null) {
            SysUser user = sysUserMapper.selectById(userId);
            if (user == null) {
                return reject(response, ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMessage());
            }
            userState = new RedisCache.UserState(user.getStatus(), currentTokenVersion(user));
            redisCache.saveUserState(userId, userState);
        }
        if (userState.status() == Constants.USER_STATUS_DISABLED) {
            return reject(response, ResultCode.FORBIDDEN.getCode(), "账号已被禁用，请联系管理员");
        }
        int claimedVersion = Integer.parseInt(String.valueOf(claims.get("tokenVersion")));
        if (claimedVersion != userState.tokenVersion()) {
            // 版本不一致：会话已被登出/改密/重置/禁用撤销，或 Token 由旧版本签发（缺失版本号的存量 Token）
            return reject(response, ResultCode.UNAUTHORIZED.getCode(), "登录已过期，请重新登录");
        }

        // Redis 会话校验（需求设计文档 2.2 第 145 行加分项）：JWT 合法、版本有效后，
        // 再确认该 Token 仍是服务端当前会话（同账号新登录覆盖旧 Token = 单点会话）——
        //  VALID：会话存在且一致，放行；
        //  INVALID：无会话、Token 不一致，或 Redis 启用但校验异常（fail closed）→ 401；
        //  SKIP：仅 redis.enable=false 的明确无状态模式；该模式没有单点会话，
        //        但登出/改密/重置/禁用仍由上方的 token_version 持久撤销。
        RedisCache.SessionStatus sessionStatus = redisCache.validateToken(userId, token);
        if (sessionStatus == RedisCache.SessionStatus.INVALID) {
            return reject(response, ResultCode.UNAUTHORIZED.getCode(), "登录已过期，请重新登录");
        }

        UserContext.set(userId, username, role);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 请求结束清除上下文，防止线程池复用串扰
        UserContext.clear();
    }

    /** 校验并解析 Token（内联 JwtUtil 校验逻辑；拦截器不注入 JwtUtil，避免与 JwtUtil 依赖链形成循环依赖） */
    private Map<String, Object> parseAndVerify(String token) {
        JWT jwt = JWTUtil.parseToken(token);
        // 算法白名单与验签器必须同时固定，禁止 setKey 按攻击者提供的 alg 头选择签名器。
        if (!"HS256".equals(jwt.getHeader(JWTHeader.ALGORITHM))
                || !jwt.verify(JWTSignerUtil.hs256(secret.getBytes(StandardCharsets.UTF_8)))) {
            throw unauthorized();
        }

        // exp 是必需的秒级整数；multiplyExact 防止极大数值乘 1000 时溢出后绕过过期判断。
        Object exp = jwt.getPayload("exp");
        if (!(exp instanceof Number)) {
            throw unauthorized();
        }
        long expMillis;
        try {
            long expSeconds = new BigDecimal(exp.toString()).longValueExact();
            expMillis = Math.multiplyExact(expSeconds, 1000L);
        } catch (ArithmeticException | NumberFormatException e) {
            throw unauthorized();
        }
        if (System.currentTimeMillis() >= expMillis) {
            throw unauthorized();
        }

        Map<String, Object> claims = jwt.getPayloads();
        validateRequiredClaims(claims);
        return claims;
    }

    /** 在拦截器转换 userId/role 前校验全部必需 claims，畸形输入统一按 401 处理。 */
    private void validateRequiredClaims(Map<String, Object> claims) {
        Object username = claims.get("username");
        if (!(username instanceof String) || StrUtil.isBlank((String) username)) {
            throw unauthorized();
        }
        validateNumericClaim(claims.get("userId"), false);
        validateNumericClaim(claims.get("role"), true);
        // tokenVersion 决定服务端撤销：必须存在、可无损转 int 且非负（缺失视为旧版 Token，401 要求重新登录）
        validateNumericClaim(claims.get("tokenVersion"), true);
        if (Integer.parseInt(String.valueOf(claims.get("tokenVersion"))) < 0) {
            throw unauthorized();
        }
        int role = Integer.parseInt(String.valueOf(claims.get("role")));
        if (role != Constants.ROLE_STUDENT && role != Constants.ROLE_ADMIN) {
            throw unauthorized();
        }
    }

    /** 用户当前 Token 版本号（数据库列为 NOT NULL DEFAULT 0，null 仅用于兼容测试替身）。 */
    private int currentTokenVersion(SysUser user) {
        return user.getTokenVersion() == null ? 0 : user.getTokenVersion();
    }

    /** 校验必需数值 claim 可被目标类型无损解析，不改变有效 Token 的字符串/数字兼容口径。 */
    private void validateNumericClaim(Object claim, boolean integer) {
        if (claim == null || StrUtil.isBlank(String.valueOf(claim))) {
            throw unauthorized();
        }
        try {
            if (integer) {
                Integer.valueOf(String.valueOf(claim));
            } else {
                Long.valueOf(String.valueOf(claim));
            }
        } catch (NumberFormatException e) {
            throw unauthorized();
        }
    }

    private BusinessException unauthorized() {
        return new BusinessException(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMessage());
    }

    /** 判断是否为管理员专属接口 */
    private boolean isAdminPath(String uri) {
        for (String prefix : Constants.ADMIN_API_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        // R3 预约审核接口（单条审核 /api/reservation/{id}/audit、批量审核 /api/reservation/batch-audit）
        // 路径不在 /api/reservation/manage 前缀下，按精确路径/正则判定，学生 Token 访问返回 403
        if (uri.startsWith(Constants.RESERVATION_BATCH_AUDIT_PATH)
                || uri.matches(Constants.RESERVATION_AUDIT_PATH_REGEX)) {
            return true;
        }
        return false;
    }

    /** 写入鉴权失败响应：HTTP 状态码 + Result 结构 */
    private boolean reject(HttpServletResponse response, int code, String message) throws Exception {
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(code, message)));
        return false;
    }
}
