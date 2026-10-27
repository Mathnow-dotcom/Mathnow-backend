# Math Now — Batch progression and assessment API

> **27 October 2026 Addition update:** Addition now has separately assessed Parts
> 1 (base levels 1–10) and 2 (base levels 11–19), each with its own 100-level cap.
> See [ADDITION_PARTS_IMPLEMENTATION.md](ADDITION_PARTS_IMPLEMENTATION.md) for the
> superseding Addition mapping, API fields, independent limits and migration behavior.
> The single-part Addition examples/table below describe the earlier version; other
> subject rules and request formats remain applicable.

Implementation reference, reviewed 26 October 2026 against the current local backend and frontend. This describes implemented behavior, not a deployment guarantee. Example IDs and timestamps are illustrative.

## 1. Scope and progression rules

Managed progression: Addition → Subtraction → Multiplication → Division → existing Fractions learning.

| Subject | Operation | Assessment type | Base levels currently configured | Maximum full batches | Final cumulative level |
|---|---|---|---:|---:|---:|
| Addition | `add` | `A` | 19 | 5 | 95 |
| Subtraction | `sub` | `B` | 11 | 9 | 99 |
| Multiplication | `mul` | `C` | 10 | 10 | 100 |
| Division | `div` | `D` | 13 | 7 | 91 |
| Fractions | `frac` | None | Existing curriculum | Not batchwise | Existing curriculum |

The 100-level rule is a cap, not a promise of exactly 100 levels per subject. Only full batches are assigned:

```text
batchSize = number of configured base levels, validated against catalog content
maxBatches = floor(100 / batchSize)
batchStart = (batch - 1) × batchSize + 1
batchEnd = batch × batchSize
baseLevel = ((cumulativeLevel - 1) mod batchSize) + 1
```

Curriculum must contain contiguous levels starting at 1, with 1–100 levels and required colored-belt facts. Missing curriculum fails validation; configuration does not generate missing content.

The curriculum is snapshotted when a subject is entered. Future changes affect new snapshots, not an existing student's subject snapshot. For a future 20-level curriculum, batch ends are 20, 40, 60, 80, 100.

Example: Subtraction Level 12 reuses base Level 1; Level 17 reuses base Level 6. Progress remains under `L12` and `L17`, never overwriting first-batch progress. Existing generators generate questions normally; repeated batches do not replay previous saved question lists. Resuming an unfinished run retains that run's questions.

### State transitions

| Event | Result |
|---|---|
| Complete a learning level | Next eligible cumulative level; existing within-level behavior remains |
| Complete every level in assigned batch | `ASSESSMENT_READY` |
| Pass assessment | Subject becomes `PASSED`; next managed subject unlocks |
| Fail assessment with batches remaining | Same subject, next full batch, `LEARNING` |
| Fail final permitted batch assessment | `INTERVENTION_REQUIRED`; no automatic extra batch/retake |
| Pause, close, or interrupt test | Saved attempt remains unfinished; no automatic fail/advance |
| Pass Division | Continue to existing Fractions learning when enabled |

New students begin the next subject at Level 1. Legacy migration may preserve already-completed levels there.

**Pass requires both conditions:** every item correct AND unrounded average response time strictly below 3 seconds. The server evaluates:

```text
correctCount == itemCount && totalMeasuredMs < 3000 × itemCount
```

Exactly 3 seconds fails. Display rounding and the “correct under 3 seconds” percentage do not determine passing. An individual answer may take longer than 3 seconds if every answer is correct and the overall average remains below 3 seconds.

## 2. Request conventions

- Base path: `/api`.
- JSON requests: `Content-Type: application/json`.
- Student authentication: `x-pin: <student PIN>`.
- Admin endpoints: `x-pin: <admin PIN>` validated against backend configuration.
- Keep PINs as strings to preserve leading zeros. Never expose real PINs in shared examples or logs.
- Cumulative learning levels are 1-based. Assessment `position` is 0-based. Report `page` is 0-based.
- JSON dates represent instants. UI timestamps use device timezone; CSV timestamps use `America/Los_Angeles` including UTC offset and daylight-saving changes.

PIN handling is not identical across routes: assessments require an existing user; user/quiz routes use the existing `PinUserResolver`, which can resolve or create a PIN user. Do not assume arbitrary PINs are rejected on every route.

## 3. Read authoritative progression

### `GET /api/user/program`

Header: student `x-pin`. No body. Reconciles persisted learning completions and recovers completed assessment transitions.

Illustrative response (subject map abbreviated):

```json
{
  "subject": "sub",
  "status": "LEARNING",
  "level": 17,
  "batch": 2,
  "subjects": {
    "sub": {
      "subject": "sub",
      "curriculum": { "baseLevels": [1,2,3,4,5,6,7,8,9,10,11] },
      "batch": 2,
      "status": "LEARNING",
      "completedLevels": [1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16],
      "results": {
        "example-attempt-id": {
          "attemptId": "example-attempt-id", "batch": 1,
          "count": 66, "correct": 65, "totalMs": 140000, "passed": false
        }
      }
    }
  },
  "grandfatheredAccess": ["add"],
  "completedLevels": { "add": [], "sub": [1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16], "mul": [], "div": [] }
}
```

`level` is the first incomplete level of the current batch, or its final level when all are complete. Set/collection ordering is not guaranteed. `grandfatheredAccess` preserves legacy access; it does not mean assessment success.

Fractions compatibility: eligible legacy Fractions students and Division graduates receive `subject: "frac"`, `status: "LEARNING"`, their saved Fractions `level`, and `batch: null`. `subjects` remains the managed four-subject history; a `subjects.frac` batch record is not created. Internal `PROGRAM_COMPLETED` may still appear when no managed subject remains and Fractions continuation is unavailable; the student UI no longer labels this “Mathematics Program Completed.”

### `GET /api/user/operations`

Returns `{ "operations": { ... } }`. Each operation includes `maxLevel`, `enabled`, `unlocked`, and `prerequisite`. Managed operations additionally include `batchSize`.

For a Subtraction snapshot of 11 levels in batch 2, `batchSize` is 11 and `maxLevel` is 22. `maxLevel` is the currently assigned cumulative ceiling, not blanket permission to enter every level. Backend eligibility still applies.

### `GET /api/user/progress`

Returns the existing operation/level/belt tree with additive display metadata:

```json
{
  "progress": {
    "_current": { "operation": "sub", "level": 17 },
    "sub": {
      "L17": { "unlocked": true, "completed": false, "white": { "unlocked": true } }
    }
  }
}
```

Example is abbreviated; existing belt fields remain. `_current` is a response projection, not a persisted level or an authorization token. Current Level, profile, and admin displays use it rather than guessing the highest unlocked subject. Belt labels come from the corresponding level node. Normalization must preserve `_current`, and level iteration must ignore it. The projection may expose the current eligible level/white belt without modifying legacy saved data.

## 4. Existing learning APIs

Batch integration reuses these APIs; it does not introduce a second learning engine. All routes below are POST with student `x-pin`.

| Route | Request fields |
|---|---|
| `/api/quiz/prepare` | `level`, `beltOrDegree`, `operation`, optional `gameMode`, `gameModeType`, `targetCorrect` |
| `/api/quiz/start` | `quizRunId` |
| `/api/quiz/answer` | `quizRunId`, `questionId`, `answer`, `responseMs`, optional `forcePass`, `skipLevelAward` |
| `/api/quiz/practice/answer` | `quizRunId`, `questionId`, `answer` |
| `/api/quiz/inactivity` | `quizRunId`, `questionId` |
| `/api/quiz/complete` | `quizRunId` |

Example preparation:

```json
{ "operation": "sub", "level": 17, "beltOrDegree": "white", "gameMode": false }
```

Use cumulative `level`, never substitute the base level in the request. The backend maps content generation internally. Colored belts, black degrees (`black-1` through `black-7`), pretests, Lightning, Surf, Rocket, Bonus, and practice retain their existing mode-specific response structures and behavior.

Prepare checks sequential subject/level/belt eligibility. Run mutations check ownership. Active-run lookups validate persisted eligibility without repeatedly reconciling history on every answer; new preparation and assessment entry still reconcile. Managed progression cannot be bypassed using `forcePass`.

Learning responses remain mode-specific: consumers handle existing fields such as `practice`, `completed`, `nextIndex`, `dailyStats`, `updatedProgress`, and `summary` where supplied. This document does not redefine them as a single uniform response schema.

## 5. Assessment APIs

### `GET /api/assessments/current?type=B`

Returns `{ "attempt": null }` or `{ "attempt": <StudentState> }`. Types are case-sensitive `A`–`D`; omitted type defaults to `A`. Fetching current state does not create a new test.

### `POST /api/assessments/start`

Used for both Start and Resume:

```json
{ "beginKey": "example-begin-uuid", "session": "example-session-uuid", "type": "B" }
```

Identifiers must match `[A-Za-z0-9-]{1,80}`. Reuse the same `beginKey` when retrying an uncertain start. The service first looks for that begin key, then an unfinished attempt of the same type. Only creation of a new attempt requires completion of the assigned batch. Existing unfinished legacy tests can resume but do not become integrated batch results.

Starting/resuming an unfinished test assigns its active session and starts/resumes timing. A second tab/session takes ownership; the old session receives a conflict when submitting.

All successful start/update responses use `StudentState`:

```json
{
  "id": "example-attempt-id", "type": "B", "completed": false,
  "position": 0, "count": 66, "problem": "5 - 2", "nextProblem": "3 - 1"
}
```

Question counts are derived from the catalog; 66 is an example, not an API constant. On completion, `position == count`, `completed` is true, and both problem fields are null. The student response does not expose correct answers, pass/fail, or batch details; refresh `/user/program` for the next progression destination.

### `POST /api/assessments/{id}`

Submit an answer:

```json
{ "session": "example-session-uuid", "position": 0, "action": "answer", "answer": "3" }
```

`answer` is a string of 1–10 decimal digits. The server computes correctness and measured response time. Unlike learning `/quiz/answer`, this endpoint does not accept client `responseMs`.

Heartbeat or pause:

```json
{ "session": "example-session-uuid", "position": 1, "action": "heartbeat" }
```

```json
{ "session": "example-session-uuid", "position": 1, "action": "pause" }
```

Actions are exactly `answer`, `heartbeat`, and `pause`. Position must be supplied and nonnegative. Pause clears the active tick; resume through `/start` reactivates timing.

Timing accumulates accepted server tick intervals. Positive intervals up to the 15-second lease are counted; longer unknown offline gaps are excluded. `answeredAt` is server acceptance time, not guaranteed device-click time. Heartbeats must continue during an active test for reliable time accounting.

### Question pool

The server reads facts for the assessment's subject and snapshotted base-level range, deduplicates operand pairs, and shuffles once when creating the attempt. Addition and multiplication include reversed operands; subtraction and division do not. Negative subtraction results and invalid division facts are rejected. The stored attempt fixes its item order for resume/retry. Subsequent batches use the same subject curriculum, not harder invented questions.

### Retry and completion guarantees

- An already-accepted lower position returns current state without adding another answer or charging its time again.
- A future position or stale active session returns 409. Reload/resume instead of incrementing locally again.
- Completed attempts return their completed state on repeated updates.
- The final answer saves the completed attempt before triggering program reconciliation.
- Processed attempt IDs and enrollment/subject/batch association prevent repeated advancement. A unique integrated-attempt index protects concurrent creation.
- Reconciliation can recover a completed assessment whose progression update was interrupted.
- No failure or batch transition occurs merely from quitting, losing connectivity, or leaving a partial test unfinished.

## 6. Admin reports and CSV

All endpoints require admin `x-pin`.

### `GET /api/admin/assessments`

| Query | Meaning |
|---|---|
| `student` | Exact student PIN; blank means all |
| `type` | Assessment type filter; blank means all |
| `from` | ISO instant, inclusive completion-time lower bound |
| `to` | ISO instant, exclusive completion-time upper bound |
| `page` | Zero-based; default 0; negative values treated as 0 |

Returns an array of up to 25 completed attempts, ordered by completion time descending, then ID descending. There is no total-count envelope. Partial attempts are not included.

Each row includes `id`, `student`, `pin`, `type`, `startedAt`, `completedAt`, `correct`, `count`, `percent`, `totalMs`, `answers`, `averageTimeMs`, `correctUnderTwoSecondsPercent`, `correctUnderThreeSecondsPercent`, `batch`, `attemptNumber`, and `passed`.

`answers` entries contain `problem`, `answer`, `correct`, `timeMs`, and `answeredAt`. Older timestamps can be null. For integrated tests, `attemptNumber` currently equals the subject batch number; it is not a global lifetime test counter. Historical standalone tests have null integration fields and display as Legacy.

Metrics use all test items as denominator. “Correct under N seconds” counts only correct items with measured time strictly less than N seconds. Accuracy displays as a percentage; raw correct/count remain in the API. Average time is `totalMs / count`.

### `GET /api/admin/assessments/export`

Accepts the same `student`, `type`, `from`, and `to` filters. Streams all matching completed attempts, not just the displayed page, as `text/csv` with attachment filename `mathnow-test-results.csv`. One row per answered item.

CSV columns, in order:

```text
Attempt,Student,PIN,Test,Started America/Los_Angeles,Completed America/Los_Angeles,
Correct,Items,Percent,Total ms,Item,Problem,Answer,Correct item,Time ms,
Average time per item seconds,Correct under 2 seconds percent,
Correct under 3 seconds percent,Answered at America/Los_Angeles,Batch,Passed
```

CSV uses quoted/escaped values and protection against spreadsheet-formula prefixes. Missing legacy values are blank. UI duration formatting does not change CSV `Total ms` / `Time ms` units.

### `GET /api/admin/assessments/progress?student=...`

Separate program summary, optionally filtered by student PIN. Returns `students`, `statuses`, `modules`, `moduleRates`, `passes`, `failures`, `passRate`, and `interventionRate`. Student rows include program-view data, student identity, and `migrationPending` when based on a read-only migration preview. The pass/fail summary covers integrated results, not every standalone historical test.

Frontend Test Results keeps history, filters, pagination, per-item details, and export first. Program progress is collapsed below the report and fetched only when expanded. Only the PIN filter applies to that summary, not test/date filters.

No assessment-delete route exists in the currently inspected admin resource; do not infer DELETE support from older discussions or UI versions.

## 7. Errors and client handling

| Status | Typical cause |
|---|---|
| 400 | Invalid assessment type/action/identifier/answer/position, invalid date, unavailable test curriculum |
| 401 | Missing/invalid assessment student PIN; missing PIN on protected user requests |
| 403 | Invalid admin PIN, wrong run owner, locked subject/level/belt, ineligible assessment |
| 404 | Assessment or owned run not found |
| 409 | Session changed, future question position, concurrent write retries exhausted |

Do not assume one error JSON format across this application. Existing learning controllers sometimes wrap unexpected failures in `ErrorResponse`, including paths that may return HTTP 200. Check the response body as well as HTTP status. Preserve the pending answer on uncertainty; reconcile server position before enabling another submission.

## 8. Persistence and legacy compatibility

`program_progress` is separate from existing user progress and assessment history. Fields: user ID `_id`, optimistic `version`, `schemaVersion`, `enrollment`, `activeSubject`, `migratedAt`, `grandfatheredAccess`, and `subjects`.

Each managed subject stores its curriculum snapshot, batch, status, completed cumulative levels, and assessment results. Assessment documents store `programSubject`, `programEnrollment`, `programBatch`, and `programPassed` for integrated attempts.

Migration is lazy and additive. Existing completed/unlocked learning progress is preserved; historical standalone tests are not retroactively graded into program transitions. Explicit admin reset/restore/delete operations archive program state in `program_progress_history`; a fresh enrollment prevents old assessment results from advancing new progress.

Legacy students with saved unlocked/completed Fractions progress continue directly in Fractions without a Division retest. This is a compatibility projection/access rule; it does not rewrite managed subject history, invent passes, or introduce Fractions assessment/batches. New students still follow the managed sequence before Fractions. Fractions must be enabled.

## 9. Frontend lifecycle and rollout notes

1. Confirm student name; show streak. Students without a valid saved theme choose one; returning students retain theirs.
2. Operations reads authoritative program state plus operation/progress data.
3. Learning opens the appropriate cumulative level; assessment-ready opens the subject Start/Resume screen.
4. Assessment shows the next supplied question immediately, but gates its submission until the prior save is acknowledged.
5. Completion returns to Operations, which rereads the program destination. Failure stays in subject; pass moves forward; final failure requires intervention.

Deploy matching backend/frontend versions. No eager production migration is required or authorized by this document. Validate on staging, including concurrent submissions, index creation, legacy Fractions accounts, final intervention, reset/enrollment isolation, and cumulative levels. Existing student data must not be reset to adopt this feature.

Source of truth: `ProgramProgressionRules`, `ProgramService`, `ProgressionService`, `QuizHelper`, `QuizService`, `AssessmentService`, `AssessmentResource`, `AssessmentAdminResource`, and `UserResource`. Relevant frontend consumers include `OperationPicker`, `ProgramLearningGate`, `AssessmentScreen`, `AssessmentReports`, and the current-progress display helpers.
