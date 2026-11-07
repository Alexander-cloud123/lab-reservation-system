package com.example.reservation.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 缓存失效必须发生在数据库提交之后的回归测试。 */
class TransactionCacheInvalidatorTest {

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    @DisplayName("无事务时立即失效")
    void afterCommit_runsImmediatelyWithoutTransaction() {
        AtomicInteger calls = new AtomicInteger();
        TransactionCacheInvalidator.afterCommit(calls::incrementAndGet);
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("事务中不提前失效，afterCommit 后恰好执行一次")
    void afterCommit_defersUntilCommit() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        AtomicInteger calls = new AtomicInteger();

        TransactionCacheInvalidator.afterCommit(calls::incrementAndGet);
        assertEquals(0, calls.get(), "提交前不能推进缓存代次");

        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("事务回滚路径不执行失效")
    void afterCommit_doesNotRunOnRollback() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        AtomicInteger calls = new AtomicInteger();

        TransactionCacheInvalidator.afterCommit(calls::incrementAndGet);
        // 模拟回滚：只触发 afterCompletion(STATUS_ROLLED_BACK)，不触发 afterCommit。
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        assertEquals(0, calls.get());
    }
}
