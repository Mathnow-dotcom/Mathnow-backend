# Integrated learning and assessment progression

Addition's original single-part rules below are superseded by
[ADDITION_PARTS_IMPLEMENTATION.md](ADDITION_PARTS_IMPLEMENTATION.md) (27 October 2026).

For the current endpoint contracts and compatibility updates, see
[BATCH_PROGRESSION_API.md](BATCH_PROGRESSION_API.md).

Implemented in the backend and the companion `mathnow-f` frontend. No deployment,
production database connection, or production migration was performed.

## Progression

The program order is Addition → Subtraction → Multiplication → Division.
Students complete existing learning levels (including the existing pretest/belt
and game-mode behavior). Completing a full batch opens that subject's assessment.

- Pass: every item correct **and** sum of measured item times `< 3000 × item count`.
  This integer comparison uses the unrounded average; exactly 3 seconds fails.
- Pass opens the next subject. For new students this starts at Level 1. Approved
  legacy migration preserves any already completed levels in that subject.
- Fail opens the next full learning batch in the same subject.
- A final failure shows **Intervention Required**, without a partial batch or retake.
- Passing Division completes the managed four-subject sequence and continues into
  existing Fractions learning when enabled. Fractions is not yet batchwise.

Batch size comes from the configured operation level count, validated against
actual catalog documents for every level and colored belt. It is snapshotted on
subject entry. Missing definitions fail closed rather than generating new facts.
Maximum batches = `floor(100 / batchSize)`; no subject-specific batch-count constants.

| Current curriculum | Batch size | Full batches | Final cumulative level |
| --- | ---: | ---: | ---: |
| Addition | 19 | 5 | 95 |
| Subtraction | 11 | 9 | 99 |
| Multiplication | 10 | 10 | 100 |
| Division | 13 | 7 | 91 |

These catalog sizes were checked in the existing seeders. A future size of 20
produces batches ending at 20, 40, 60, 80, 100 for new subject entries. Existing
subject snapshots are not resized by a configuration change. Catalog/configuration
updates must be coordinated: increasing a setting alone does not invent a curriculum.

## Learning content versus saved progress

`baseLevel = ((cumulativeLevel - 1) % snapshotBatchSize) + 1`.
For example, Addition 20 maps to base 1 and Addition 39 maps to base 1 again.

- Quiz runs, attempts, and progress keys retain cumulative levels (`L20`, `L21`, etc.),
  so a repeated level never overwrites the earlier `L1`, `L2`, etc.
- Existing Normal, black-belt, Pretest, Lightning practice, Surf, Rocket and Bonus
  builders receive base levels. Their question-generation logic is reused.
- Level-one special cases, prior-level pools and pretest timing use the base level.
- New runs generate new questions normally. Existing unfinished runs still resume.
- Level cards retain cumulative labels but reuse base-level instructional videos
  and theme visuals. Program navigation targets the first unfinished level.
- The managed subject list is only `add`, `sub`, `mul`, `div`. The existing `frac`
  operation and its generator paths remain outside these gates. There is no separate
  `Factors` operation defined in this checkout.

## Persistence, migration and recovery

New collection: `program_progress`, keyed by user ID, with optimistic `version`.
It stores an enrollment ID, schema version, migration date, grandfathered access,
active subject, per-subject curriculum snapshots, batch, completed cumulative
levels, status and processed assessment results.

Migration is additive and lazy on the student's first program access:

1. New accounts are explicitly marked as integrated enrollments.
2. Existing accounts retain previously unlocked subjects/levels. Start from their
   current/highest accessed subject, or an already available successor when that
   subject was complete. Import completed levels without rewriting legacy data.
3. Previously unlocked gaps remain accessible, but all batch levels must be complete
   before an integrated assessment is eligible.
4. Historical standalone results are not retroactively used to pass/fail the program.
   Existing unfinished assessments remain resumable and do not advance program state.
5. Repeating migration reads the same unique user document. Concurrent creation is
   protected by MongoDB's unique `_id`.

Completed integrated assessments are a durable transition journal. After saving
the final answer, the service applies the result with optimistic locking. If a
process stops between those writes, the next program read recovers the transition.
An enrollment/subject/batch unique index prevents multiple integrated attempts for
the same batch. Position-based answer retries and processed attempt IDs prevent
duplicate grading or a second batch advance.

Interrupted assessments retain the existing heartbeat/pause/resume policy: no
failure or advancement until every item is answered. Unknown offline gaps beyond
the existing 15-second lease are excluded from measured active response time.

Existing explicit reset/restore/delete actions archive program state in
`program_progress_history` before removing its active document. A fresh enrollment
ID prevents an old assessment from advancing newly reset/restored progress.
Assessment history itself is not rewritten by this integration.

## API and UI

- `GET /api/user/program` (`x-pin`): authoritative subject, level, batch, status,
  snapshots, completed levels and grandfathered access. Reconciles durable completions.
- `GET /api/user/operations`: existing response plus per-subject `batchSize`;
  `maxLevel` reflects the currently assigned full batch's cumulative upper bound.
- Existing `/api/quiz/*` endpoints: prepare/resume/start/answer paths enforce subject,
  level and belt eligibility; run-mutating endpoints verify the owning PIN.
- Existing `/api/assessments/start`: requires full-batch completion for a new attempt;
  binds the attempt to its enrollment, subject and batch. Existing retries/resume work.
- Existing `/api/assessments/{id}`: final answer persists strict pass/fail and triggers
  recoverable program advancement. Partial submissions do not advance progress.
- `GET /api/admin/assessments/progress` (admin `x-pin`, optional `student` PIN): student
  progress, module counts/rates, pass/fail counts and pass rate, intervention count/rate.
  Includes a **read-only migration preview** for students without a program record.
- Existing admin reports/CSV add batch and pass/fail. One integrated attempt is allowed
  per batch, so batch number is also its subject attempt number. Historical rows remain
  marked Legacy. Existing accuracy, average time, fast-correct metrics and timestamps
  remain; CSV timestamps retain America/Los_Angeles, UI retains device timezone.

Students go from login and the existing streak animation directly to `/operations`.
The operation page selects the backend's current subject. Selecting it opens levels
while learning, or the subject's test start/resume screen when assessment-ready.
After the existing test completion screen, students return to operations: the same
subject after failure or the next subject after passing. Final intervention and
managed-sequence completion are handled without claiming all mathematics is complete. Old `/mode` and `/test-selection`
URLs redirect to operations; there is no separate mode page. Assessment instructions, on-screen keypad,
instant next-question display, save acknowledgement gate, quit/resume, streak flow
and global app timer are retained. Intervention and program completion have explicit
student states. Admin reports show module, cumulative level, batch, completed levels,
subject access/status, assessment results and summary metrics.

## Verification

- `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew test bootJar`
- `npm run build`
- `node scripts/test-assessment-browser.mjs`
- `git diff --check` in both repositories.

Focused tests cover dynamic level mapping, exact accuracy/time boundaries, sequence,
final intervention, interrupted attempts, duplicate results, optimistic-lock recovery,
legacy migration/access, enrollment isolation, missing curriculum, backend access
denials, MongoDB converter round trips and actual repeated-batch question builders.
The isolated browser harness covers responsive program navigation, cumulative Level
20, assessment-ready/intervention/completed states, all four assessment types,
instant submission/retry, quit/resume, refresh/draft recovery and admin filters/export.

## Rollout notes / remaining verification

Tests use isolated mocks and real MongoDB mapping conversion, not a live database.
Before production rollout, run the full frontend/backend flow against a staging
MongoDB copy, inspect migration previews and verify index creation and concurrent
requests there. Deploy matching frontend/backend versions together. Keep the existing
destructive multiplication-reset migration flag disabled. No production migration
command is required for the new lazy migration; do not reset student collections.

Pre-existing build warnings remain (old browserslist data, deprecated Vite glob
options, mixed static/dynamic imports and Gradle deprecation warnings).
