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
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.context.TransactionCacheDb
import org.moqui.impl.entity.EntityDefinition
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Runs a closure inside the same held overlay that enter_sim uses: entity writes go to an in-memory database that is
 * discarded, async work and service jobs are skipped, entity data feeds are off. Returns what the closure returned and
 * what it changed. Encrypted fields are masked in the change list.
 */
class SimRunner {
    private static final Logger logger = LoggerFactory.getLogger(SimRunner.class)

    /** Result keys: ok, error (when not ok), result, changes (operation, entityName, pk, values). */
    static Map<String, Object> run(ExecutionContext ec, Closure work) {
        ExecutionContextImpl eci = (ExecutionContextImpl) ec
        if (eci.simSession || ec.entity.isTxCacheActive())
            throw new IllegalStateException('A simulation or transaction cache is already active')
        Map<String, Object> out = [ok: true] as Map<String, Object>
        ec.entity.startTxCacheDb(true)
        eci.simSession = true
        boolean feedWasOff = eci.artifactExecutionFacade.disableEntityDataFeed()
        try {
            try {
                out.result = work.call()
            } catch (Throwable t) {
                out.ok = false
                out.error = t.message ?: t.class.name
            }
            out.changes = collectChanges(ec, eci)
        } finally {
            eci.simSession = false
            if (!feedWasOff) eci.artifactExecutionFacade.enableEntityDataFeed()
            try { ec.entity.stopTxCache() } catch (Throwable t) { logger.warn("Simulation overlay stop failed: ${t.message}") }
        }
        out
    }

    private static List<Map<String, Object>> collectChanges(ExecutionContext ec, ExecutionContextImpl eci) {
        List<Map<String, Object>> changes = ((TransactionCacheDb) eci.entityTxCache).describeChanges()
        boolean disabled = ec.artifactExecution.disableAuthz()
        try {
            for (Map<String, Object> change : changes) {
                if (change.operation == 'delete') continue
                try {
                    EntityDefinition ed = (EntityDefinition) ec.entity.getEntityDefinition(change.entityName as String)
                    def row = ec.entity.find(change.entityName as String).condition(change.pk as Map).useCache(false).one()
                    if (row == null) continue
                    Map<String, Object> values = new LinkedHashMap<>()
                    row.getMap().each { k, v ->
                        if (v == null) return
                        values[k as String] = ed.getFieldInfo(k as String)?.encrypt ? '***' : v
                    }
                    change.values = values
                } catch (Throwable t) {
                    change.valuesError = t.message
                }
            }
        } finally {
            if (!disabled) ec.artifactExecution.enableAuthz()
        }
        changes
    }
}
