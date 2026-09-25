package com.example.reservation.common;

/**
 * 当前登录用户上下文（ThreadLocal）
 * 由鉴权拦截器在请求进入时写入，请求结束后清除，避免线程复用导致的数据串扰
 *
 * @author reservation-team
 */
public class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    /** 写入当前登录用户 */
    public static void set(Long userId, String username, Integer role) {
        HOLDER.set(new LoginUser(userId, username, role));
    }

    /** 获取当前登录用户，未登录返回 null */
    public static LoginUser get() {
        return HOLDER.get();
    }

    /** 获取当前用户 ID */
    public static Long getUserId() {
        LoginUser user = HOLDER.get();
        return user == null ? null : user.userId();
    }

    /** 当前登录用户是否为管理员（未登录返回 false；R3/R5 用途可见性共用） */
    public static boolean isAdmin() {
        LoginUser user = HOLDER.get();
        return user != null && user.role() != null && user.role() == Constants.ROLE_ADMIN;
    }

    /** 给定用户 ID 是否为当前登录用户本人（未登录返回 false；R3/R5 用途可见性共用） */
    public static boolean isSelf(Long userId) {
        LoginUser user = HOLDER.get();
        return user != null && user.userId() != null && user.userId().equals(userId);
    }

    /** 请求结束后清除 */
    public static void clear() {
        HOLDER.remove();
    }

    /** 登录用户信息载体 */
    public record LoginUser(Long userId, String username, Integer role) {
    }
}
