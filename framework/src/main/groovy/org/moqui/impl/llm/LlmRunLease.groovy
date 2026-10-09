/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.impl.llm

import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.context.UserFacadeImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * Keeps the lease of a run alive while a worker executes it, so the recovery job does not take a live run over.
 * Renewal runs in its own thread and execution context under the run owner's identity. If a renewal is refused
 * (another worker holds a newer fencing token) {@link #isLost()} turns true and the worker must stop.
 */
final class LlmRunLease implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(LlmRunLease.class)
    static final int LEASE_SECONDS = 60

    private final ExecutionContextFactoryImpl ecfi
    private final String username, runId, workerId
    private final long fence
    private final ScheduledExecutorService scheduler
    private volatile boolean lost = false

    private LlmRunLease(ExecutionContextFactoryImpl ecfi, String username, String runId, String workerId, long fence) {
        this.ecfi = ecfi; this.username = username; this.runId = runId; this.workerId = workerId; this.fence = fence
        this.scheduler = Executors.newSingleThreadScheduledExecutor({ Runnable r ->
            Thread t = new Thread(r, "llm-run-lease-${runId}"); t.daemon = true; t } as ThreadFactory)
    }

    /** Takes the lease of a running run and starts renewing it. Close it when the worker stops executing. */
    static LlmRunLease acquire(ExecutionContext ec, String runId, String workerId) {
        long fence = LlmRunStore.acquireLease(ec, runId, workerId, LEASE_SECONDS)
        start(ec, runId, workerId, fence)
    }

    /** Starts renewing a lease the caller already holds (a run claimed by recovery or by a job). */
    static LlmRunLease start(ExecutionContext ec, String runId, String workerId, long fence) {
        LlmRunLease lease = new LlmRunLease((ExecutionContextFactoryImpl) ec.factory, ec.user.username, runId, workerId, fence)
        long period = Math.max((long) (LEASE_SECONDS / 3), 1L)
        lease.scheduler.scheduleAtFixedRate({ lease.renew() } as Runnable, period, period, TimeUnit.SECONDS)
        lease
    }

    long getFence() { fence }
    String getWorkerId() { workerId }
    boolean isLost() { lost }

    private void renew() {
        ExecutionContextImpl tec = null
        try {
            tec = ecfi.getEci()
            if (!((UserFacadeImpl) tec.user).internalLoginUser(username, false)) return
            LlmRunStore.renewLease(tec, runId, workerId, fence, LEASE_SECONDS)
        } catch (IllegalStateException e) {
            lost = true
            logger.warn("LLM run ${runId} lost its lease: ${e.message}")
        } catch (Throwable t) {
            logger.warn("LLM run ${runId} lease renewal failed: ${t.message}")
        } finally {
            tec?.destroy()
        }
    }

    @Override void close() { scheduler.shutdownNow() }
}
