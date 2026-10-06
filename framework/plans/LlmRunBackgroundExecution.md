# LlmRun background execution

Scope: series B of the Agent Executor work. Background execution of an existing `LlmRun` through ServiceJob, and resume after a human confirmation. Everything here uses framework entities only (`moqui.llm.*`, `moqui.service.job.*`).

## 1. Prerequisite check

Checked on branch `agent-executor`, based on the unified Open Responses model (`224321af`).

| Question | Answer | Evidence |
| --- | --- | --- |
| 1. Is there a service that executes an existing queued `LlmRun` in a ServiceJob as `LlmRun.userId`? | **No.** | 
A run is created as a side effect of a client turn (`LlmClientImpl.beginDurableRun`) and driven by that same call. 
`create#LlmRun` only inserts a `LlmRunQueued` row; nothing reads `objective` to start an agent loop. 
The only code that drives an existing run is `LlmRecoveryWorker.recover`, which claims runs in `LlmRunRunning`/`LlmRunRecovering` with an expired lease, 
never `LlmRunQueued`, and only replays checkpoints in phase `ready_provider`. |
| 2. Is there a way to resume a run in `LlmRunWaitConfirm` or `LlmRunWaitClient` after a human answer? | 
**Partially, and not for this use.** | `LlmRunStore` allows `WAIT_CONFIRM -> RUNNING`, 
and `beginDurableRun(resume=true)` moves a waiting run back to running, but only for a run attached to a conversation 
through the `activeLlmRunId` attribute and only when a client posts tool results (`resume#Conversation`).
 There is no service that records a decision or answer on the run, and `update#LlmRunStatus` 
 is `allow-remote="false"` and has no owner-confirmation semantics. |
| 3. Is `LlmRun` linked to `ServiceJobRun`? | **No.** | `LlmRun` has no `jobRunId`; 
no join entity exists. The only LLM ServiceJob is `recover_AllLlmRuns_frequent`; 
`LlmRunStoreTests` verifies it creates a `ServiceJobRun`, but nothing ties a run to a job run. |

Consequence: series B is needed in full.

## 2. What series B adds

1. `execute#LlmRun` (`authenticate="true"`, `transaction="ignore"`, `allow-remote="false"`), input `runId`. 
The caller must be the run owner or the job thread logged in as the owner. 
It claims the run with the existing lease and fencing token, attaches it to a client built from the 
run's `profileName`, `objective` and checkpoint (the same path as `attachRecoveredRun`), 
runs the agent loop until the run completes, fails, is cancelled or waits, then releases the lease.
2. ServiceJob `execute_LlmRun` in `MoquiSetupData.xml` (no cron, long transaction timeout, topic `LlmRunEvents`),
 started with `ec.service.job("execute_LlmRun").parameters([runId: ...]).run()`.
3. `answer#LlmRun` and `confirm#LlmRun`: owner only (or a permission passed in by the calling service). 
They record the answer or decision in the status journal and checkpoint, move the run to `LlmRunQueued`,
 and start a new job run.
4. Link `LlmRun` to its job run: field `jobRunId` on `LlmRun` 
(nullable, no foreign key to keep the entities decoupled).

5. Durability. A ServiceJob started with `.run()` waits in an in-memory queue, so a restart loses it and leaves a `ServiceJobRun` without `endTime`. `LlmRun` stays the source of truth. `recover#AllLlmRuns` is extended to re-dispatch `LlmRunQueued` runs that have no live job (the run has no `jobRunId`, or its `ServiceJobRun` has ended or is older than a grace period) in addition to the expired-lease runs it already recovers.
6. Concurrency. `execute#LlmRun` takes a semaphore sized from the profile's `pool-max`, so executor runs cannot starve the shared `jobWorkerPool`. A run that cannot get a permit goes back to `LlmRunQueued` and is picked up by the recovery job.

## 3. Tests

Offline, with the fake provider used by the Open Responses client tests: queued run executes in a job as its owner; the job thread user equals the owner; a run that waits ends the job; `confirm#LlmRun` resumes it; cancel during execution stops at the next iteration; a notification is emitted on `LlmRunEvents`; a queued run without a live job is re-dispatched by recovery; the semaphore limits concurrent runs.

## 4. Open design point

`execute#LlmRun` must not call `LlmClientImpl.call()` blindly for a run whose `contextJson` is empty. A queued run created from an `objective` has no items yet, so the executor builds the first input from `objective` before the loop starts.
