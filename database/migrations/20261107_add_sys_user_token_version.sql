-- =====================================================================
-- 迁移：sys_user 增加 token_version（Token 版本号，服务端持久撤销会话）
--
-- 背景：纯 JWT 无法在到期前主动作废；Redis 会话仅在 Redis 可用时生效。
--       本字段使「登出 / 改密 / 管理员重置 / 禁用」在数据库层留下不可回退的撤销凭据：
--       JWT 内携带 tokenVersion，拦截器与库中当前值比对，不一致即 401。
--       因此 Redis 关闭、进程重启、多实例部署下旧 Token 同样立即失效。
--
-- 适用：已存在的数据库（docker 数据卷 reservation-mysql-data 已初始化时，
--       database/init_db.sql 不会再次执行，需手工执行本脚本一次）。
-- 幂等：列已存在时不做任何改动，可安全重复执行。
-- 只读/写 sys_user 一列，不改动其它表。
-- =====================================================================

USE reservation;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sys_user'
      AND COLUMN_NAME = 'token_version'
);

SET @ddl := IF(
    @col_exists = 0,
    'ALTER TABLE sys_user ADD COLUMN token_version INT NOT NULL DEFAULT 0 COMMENT ''Token 版本号：登出/改密/重置/禁用时自增，JWT 携带并比对，旧 Token 立即失效'' AFTER status',
    'SELECT ''token_version 已存在，跳过'' AS migration'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 复核：应看到 token_version 列，且存量用户默认 0
SHOW COLUMNS FROM sys_user LIKE 'token_version';
SELECT COUNT(*) AS users_with_default_version FROM sys_user WHERE token_version = 0;
