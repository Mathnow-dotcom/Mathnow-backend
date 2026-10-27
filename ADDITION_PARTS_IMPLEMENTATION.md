# Addition: two independently assessed parts

Implemented 27 October 2026. No deployment or production migration was run.

## Rules

- Part 1: base levels 1–10. A failed test assigns another full 10-level batch
  of the same content. At most 10 batches (100 learned levels in this part).
- Part 2: base levels 11–19 in today's curriculum. A failed test assigns another
  full 9-level batch. At most 11 batches (99 learned levels, under its own 100 cap).
- Passing Part 1 opens Part 2, not Subtraction. Passing Part 2 opens Subtraction.
- Pass criteria remain all answers correct AND total measured milliseconds strictly
  less than `3000 * questionCount`. Final failure in either part requires intervention.
- Each subject-entry snapshot retains its configured curriculum. The Addition split
  boundary is 10; Part 2 uses the remaining snapshotted levels. Other subjects retain
  the prior single-part behavior. A curriculum with at most ten Addition levels uses
  the existing single-part rules rather than creating an empty Part 2.

## Level numbering and content

Saved/displayed learning levels remain cumulative across Addition so repeats cannot
overwrite earlier progress. On first-attempt success, Part 2 begins at Level 11.
After one Part 1 failure and a pass at Level 20, Part 2 begins at Level 21, mapped to
base Level 11. Part 2 repeats always map to its own base levels 11–19.

`Curriculum.additionPart2Start` is null for legacy/single-part snapshots, zero for
Part 1, and the cumulative start of Part 2 once eligible. Internal batch identities
1–10 identify Part 1 and 11 onward identify Part 2. `partBatch` is the human-facing
one-based batch within the part. This preserves uniqueness without changing indexes.

The existing learning generators are unchanged. They receive the mapped base level.
`GET /user/operations` adds Addition `contentLevels` for frontend videos/theme assets;
the frontend must use this mapping instead of a single modulo across both parts.

## Assessments and APIs

Both parts retain assessment type `A` and `/test-a`. The server selects the eligible
part; the client cannot choose a part to bypass eligibility. Existing start, answer,
heartbeat, pause and resume request shapes remain unchanged.

- Part 1 pool queries only catalog levels 1–10: currently **114** unique questions.
- Part 2 pool queries only catalog levels 11–19: currently **99** unique questions.
- Addition mirroring, deduplication, shuffling, timing and retry logic are retained.
- `GET /user/program` adds `part`, `partBatch` and `assessmentLevels`; `batch` retains
  its internal stable identity. `subjects.add` retains completions and results for both parts.
- Assessment student responses add nullable `part` and `partBatch`.
- Assessment documents add `programPart` and `programPartBatch`.
- Admin attempt rows add `part` / `partBatch`. Test A filters include both parts.
- CSV appends `Addition part` and `Part batch`, retaining all earlier columns/units.
- The Operations and test-start screens label Addition Part 1/Part 2. Reports show
  part and within-part batch. Legacy tests have no invented part label.

New split-Addition attempts use a versioned enrollment scope
`<program-enrollment>:addition-parts-v1`. Existing compound uniqueness and durable
transition queries therefore cannot mistake old full-Addition results for a new
part result. Completed old assessment records and their outcome fields are untouched.

## Non-destructive migration

- New Addition entrants begin Part 1.
- Existing active Addition students at or below Level 10 keep their completed levels
  and partial belt progress in Part 1.
- Existing active Addition students beyond Level 10 enter Part 2 without a Part 1 retest.
  In the original first cycle, saved L11–L19 nodes, partial belts and completed levels
  remain in place (for example, a student at L17 remains there).
- Students already in an old repeated 19-level batch keep all old nodes and their
  original modulo-19 mapping for review/resume. Their new Part 2 range starts after
  the highest accessed/current old cumulative level, avoiding any overwrites.
- Already-passed Addition and students active in later subjects are not relocked.
- Existing unfinished full-Addition assessments can finish/resume unchanged. They do
  not count as either new part assessment; completing one returns the student to the
  current eligible part flow. Nothing silently fails, deletes, or replaces those tests.
- Conversion is lazy, guarded by optimistic version writes and the persisted split
  snapshot marker. Repeated access does not repeat migration. It does not save or
  reset the user's legacy learning document.

## Validation

Tests cover both part limits, stage-specific repeats, exact 3-second failure, passing
to the next part/subject, duplicate results, interrupted/ineligible grading, actual
catalog pools, server catalog query ranges, start retries, old-document Mongo mapping,
migration idempotence, existing L17 and repeated-batch mapping, and legacy-result isolation.

Frontend checks:

```sh
npm run build
ADDITION_PARTS_ONLY=1 node scripts/test-assessment-browser.mjs
REPORT_ONLY=1 node scripts/test-assessment-browser.mjs
```

The browser harness uses mocked APIs. Backend tests do not connect to production.
Before deployment, verify real MongoDB optimistic-lock behavior, indexes and a copy
of representative legacy accounts in staging. Deploy frontend and backend together.
