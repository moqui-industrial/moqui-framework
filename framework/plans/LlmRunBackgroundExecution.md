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

## 3. Implementation

| Piece | Where |
| --- | --- |
| `LlmRun.jobRunId` | `LlmEntities.xml`; also returned by `LlmRunStore.getRun` |
| `claimQueued`, `releaseLease`, `resumeWaiting`, `isCancelRequested`, `linkJobRun`; transitions `LlmRunWaitClient`/`LlmRunWaitConfirm` to `LlmRunQueued` | `LlmRunStore.groovy` |
| `LlmRunExecutor.start/dispatch/execute/suspendCurrent`, lease heartbeat, per-profile permits | `LlmRunExecutor.groovy` |
| Services `start`, `execute`, `answer`, `confirm`, `suspend` (noun `LlmRun` / `CurrentLlmRun`) | `LlmServices.xml` |
| ServiceJob `execute_LlmRun` and notification topic `LlmRunEvents` | `MoquiSetupData.xml` |
| ADMIN authorization on those services | `LlmTypeData.xml` (group `LlmRunServices`) |
| Re-dispatch of orphaned queued runs | `LlmRecoveryWorker.orphanedQueued/redispatch`, called from `recoverAll` |
| Profile `allowed-service` parsing (`ProfileState.allowedServices`) and `ServiceCallTool` description | `LlmFacadeImpl`, `ServiceCallTool` |
| Loop stops after a tool round when the run was put into a waiting state; background cancel check; an attached run is continued, not recreated | `LlmAgentLoop`, `LlmClientImpl` |

How it behaves:

- `start#LlmRun` creates a queued run whose trajectory already holds the objective as a user item, then dispatches the job as the run owner.
- `execute#LlmRun` refuses a run that is not queued, takes a permit from a semaphore sized by the profile `pool-max` (a run that cannot get one stays queued), claims the run (fence + 60 s lease), starts a heartbeat that renews the lease every 20 s so `recover#AllLlmRuns` does not take a live run over, attaches the stored trajectory to a client and calls the agent loop outside any transaction. Tools: the built-in ones the profile allows (`find_skill`, `browse`, `run_service`, `enter_sim`, `find_basic`) and one typed tool per `allowed-service`. A failure marks the run failed and is rethrown so the job records the error; a cancellation does not.
- A tool service ends the run's active period by calling `suspend#CurrentLlmRun` (`LlmRunWaitConfirm` or `LlmRunWaitClient`). The loop checks the run status after each tool round, returns a yielded response without finishing the run, the lease is released and the job ends, which sends the `LlmRunEvents` notification to the initiator.
- `confirm#LlmRun` (approved or not) and `answer#LlmRun` are owner only. Approval or an answer appends a user item to the trajectory (`Confirmation: approved. <comments>` or the answer), sets the run queued with checkpoint phase `ready_provider` and dispatches a new job. Rejection cancels the run and starts nothing. Both refuse while a tool invocation is planned, running or uncertain. A calling component that did its own permission check can use `LlmRunStore.resumeWaiting(..., allowNonOwner = true)`.
- Cancelling a background run (`cancel#LlmRun`) is noticed at the next `throwIfCancelled`, so within one provider call or tool.
- `recover#AllLlmRuns` also re-dispatches runs that stayed queued for more than 120 s with no live job (never started, or the linked `ServiceJobRun` ended).

## 4. Findings that changed the plan

1. The profile element `allowed-service` is in the XSD but was never read: no code turned it into a tool. It is now parsed into `ProfileState.allowedServices`, and only `execute#LlmRun` attaches those tools (the servlet still does not, as the XSD says).
2. `ServiceCallTool` refuses services without `allow-remote="true"`. A service used as a typed tool must be remote-allowed, so it has to protect itself. `suspend#CurrentLlmRun` is remote-allowed and only throws outside an agent loop.
3. Starting a job checks the caller's authorization on the service; `execute#LlmRun` and friends need an `ArtifactAuthz` for the user group (seed data grants ADMIN only; the executor component grants its own groups).
4. The run is linked to the job after `run()` returns, so a very fast job can finish before `jobRunId` is written; nothing depends on the order.
5. Only `LlmContLocal` runs are supported: a remote (`previous_response_id`) run would need the pending items re-sent after a wait.
6. Background runs use the item trajectory (`activeRunContext`). Protocols that rebuild requests from the message window only (chat completions) do not see a resumed trajectory; the executor profile must use a Responses protocol.

## 5. Tests

`LlmRunExecutionTests` (fake provider, real ServiceJob): queued run executes as its owner and completes with a notification; run that asks for confirmation ends its job and `confirm#LlmRun` resumes it with the decision in its trajectory; an answer resumes a run waiting for the client; a rejection cancels and calls the provider no more; cancelling a running run stops it, a duplicate `execute` does nothing; an orphaned queued run is re-dispatched by recovery; the profile `pool-max` keeps a second run queued while the first runs.

`LlmRunStoreTests` still covers the store; all `Llm*`, `A2A*` and `org.moqui.impl.llm.*` tests pass (338 run, 4 optional/live tests skipped).

## 6. What the executor component needed besides this series

Small additions on the same branch, used by `moqui-agent-executor` (see its `plans/AgentExecutor.md`):

- `SimRunner.run(ec, closure)`: the held overlay of `enter_sim` as a reusable call. It returns the closure's result and every row it would have created, updated or deleted (`TransactionCacheDb.describeChanges`, with the values read back and encrypted fields masked), then discards everything. It refuses to start inside another simulation. Tested in `SimRunnerTests`.
- `LlmRunExecutor.currentRun(ec)`: the durable run the calling tool service belongs to; it throws anywhere else. Services that a profile exposes as typed tools must be `allow-remote`, so each one starts with this call.
- `LlmRunStore.getRun`, `linkJobRun` and `LlmRunExecutor.dispatch` take `anyOwner` for a calling service that has already decided who may act (a code publisher resuming someone else's run).
- `ServiceCallTool` now returns the failure of a service (`ec.message` errors) to the model as `{error: ...}` and clears it. Before, a failed service gave an empty result and left the error on the context.
- `service.location` is cleared per service name by the component's publish step. The cache also drops idle entries: `MoquiDevConf.xml` sets `expire-time-idle="10"` seconds, so a temporary staged definition must be put again right before every call; the component does that.
- `UrlResourceReference.getExists()` remembers a positive answer. A published file that is later deleted from disk is still found until `resource.reference.location` is cleared or the server restarts. Publishing and replacing files is not affected.
