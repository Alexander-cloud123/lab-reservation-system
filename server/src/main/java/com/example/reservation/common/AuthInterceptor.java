package com.example.reservation.common;

import cn.hutool.core.util.StrUtil;
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

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 登录鉴权拦截器
 * 规则（spec.md 2.5）：
 *  1. 除登录/注册外，/api/** 全部要求携带合法 Token，否则返回 HTTP 401 + Result{code:401}
 *  2. 管理员专属接口前缀（/manage、/stats 等；R7 起 /api/ai 不再要求管理员角色）额外校验角色，学生 Token 访问返回 403
 *  3. 通过后写入 UserContext 供业务层获取当前用户
 *
 * @author reservation-team
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${app.jwt.secret}")
    private String secret;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private RedisCache redisCache;

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

        // 用户状态校验：账号被禁用/删除后，已签发 Token 立即失效（登录只在登录时校验状态，此处每请求兜底）
        // M10 修复：用户状态先读 Redis 短缓存（60s TTL），未命中才回源查库并回填，
        // 消除"每请求一次 selectById"的数据库单点开销；管理员禁用账号时主动删缓存，禁用仍即时生效
        Integer userStatus = redisCache.getUserStatus(userId);
        if (userStatus == null) {
            SysUser user = sysUserMapper.selectById(userId);
            if (user == null) {
                return reject(response, ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMessage());
            }
            userStatus = user.getStatus();
            redisCache.saveUserStatus(userId, userStatus);
        }
        if (userStatus == Constants.USER_STATUS_DISABLED) {
            return reject(response, ResultCode.FORBIDDEN.getCode(), "账号已被禁用，请联系管理员");
        }

        // Redis 会话校验（需求设计文档 2.2 第 145 行加分项）：JWT 合法、账号状态正常后，
        // 再确认该 Token 仍是服务端当前有效会话——
        //  VALID：会话存在且一致，放行；
        //  INVALID：Redis 正常但无此会话（已登出 / 会话过期 / 被同账号新登录顶掉）→ 401；
        //  SKIP：redis.enable=false 或 Redis 连接异常 → 降级为仅 JWT 校验，不阻断业务。
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
        cn.hutool.jwt.JWT jwt = cn.hutool.jwt.JWTUtil.parseToken(token);
        if (!jwt.setKey(secret.getBytes(StandardCharsets.UTF_8)).verify()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMessage());
        }
        // 过期校验：verify() 仅验签名，需显式校验 exp（JwtUtil.generateToken 设置 24h 有效期）
        Object exp = jwt.getPayload("exp");
        if (exp != null) {
            long expMillis;
            try {
                expMillis = Long.parseLong(String.valueOf(exp)) * 1000L;
            } catch (NumberFormatException e) {
                throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMessage());
            }
            if (System.currentTimeMillis() >= expMillis) {
                throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMessage());
            }
        }
        return jwt.getPayloads();
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
        response.getWriter().write(MAPPER.writeValueAsString(Result.error(code, message)));
        return false;
    }
}
