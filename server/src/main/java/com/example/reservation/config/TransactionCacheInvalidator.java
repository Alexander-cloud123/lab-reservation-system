package com.example.reservation.config;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

/**
 * 事务缓存失效协调器。
 *
 * <p>事务存在时仅在提交成功后执行缓存代次失效，避免提交前推进代次后，
 * 并发读将旧数据库值回填到新代次；无事务时保持立即失效。</p>
 *
 * @author hyr
 */
public final class TransactionCacheInvalidator {

    private TransactionCacheInvalidator() {
    }

    /**
     * 在当前事务提交后执行失效动作；回滚不执行，无事务时立即执行。
     */
    public static void afterCommit(Runnable invalidation) {
        Objects.requireNonNull(invalidation, "invalidation");
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            invalidation.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invalidation.run();
            }
        });
    }
}
