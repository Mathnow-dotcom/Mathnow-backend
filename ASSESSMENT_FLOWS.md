# Independent tests and learning assessments

Students choose **Learn** or **Take a test** after login/streak/theme onboarding.

## Learning flow (unchanged)

Operations → current learning levels → mandatory assessment after a full batch.
Addition keeps its two parts, independent limits and part-specific question pools.
Passing requires 100% accuracy and an unrounded average below 3 seconds.
Only these assessments affect batch retries, subject unlocking and intervention.

## Independent test flow

Mode → Take a test → Addition / Subtraction / Multiplication / Division.
These tests are available regardless of learning eligibility. Each takes a fresh
snapshot of the full subject catalog using the existing unique-question builder.
Addition covers both parts (213 questions in the current catalog).
No program enrollment, subject, batch, part or pass decision is assigned.
Completing, failing, pausing or repeating these tests never advances learning.
Global app usage tracking continues as before.

## API

PIN authentication and existing endpoints remain in use:

- `GET /api/assessments/current?type=A&flow=legacy`
- `POST /api/assessments/start` with
  `{"type":"A","flow":"legacy","beginKey":"<unique-key>","session":"<tab-key>"}`
- `POST /api/assessments/{id}` retains its existing answer/heartbeat/pause payload.

Types A/B/C/D mean Addition/Subtraction/Multiplication/Division.
`flow` accepts `learning` or `legacy`; omission defaults to `learning` for existing
clients. Unknown values are rejected. Learning starts retain backend eligibility.
Update behavior is determined by the saved attempt, not a client-supplied flow.
Begin keys cannot be reused across flows or subjects. Answer retries retain their
existing position-based idempotency and session ownership checks.

Each subject may have one unfinished attempt **per flow**. Current/resume queries
classify attempts by whether `programBatch` exists with a non-null value. Separate
frontend routes and request keys prevent one flow from resuming the other.

## Admin and history

The existing history, filters, per-item timing, accuracy, response timestamps and
CSV export include both flows. Independent attempts display **Legacy test**;
learning attempts retain batch/part numbers and pass/fail. The report API adds
`flow`; CSV appends `Test flow` without removing existing columns. Existing
completed records are not rewritten or reclassified.

## Startup compatibility

The former unique unfinished-attempt index on `(userId,type)` must be replaced,
otherwise MongoDB would prevent simultaneous independent and learning tests.
Startup idempotently sets a `flow` namespace on unfinished records only, creates
the new unique partial `(userId,type,flow)` index, then removes the old two-field
unique index. The begin-key and learning-batch unique indexes are retained.
No learning progress or completed assessment is reset. No production migration
or deployment was performed during implementation.

Deploy the matching backend before the frontend, with an orderly backend restart
(avoid mixing old and new writers during the index transition). Database credentials
must permit index creation/removal, as required by the existing startup index setup.

## Verification

- Backend: separate current/resume queries, old unfinished records, cross-flow key
  rejection, full catalog queries for all subjects, idempotent legacy completion,
  no legacy progression calls, learning completion and active-index replacement order.
- Existing Addition part, eligibility and progression regression tests.
- Mock-API browser: mode/selection at mobile and desktop widths, concurrent paused
  learning/legacy attempts, independent completion and all four subject entries,
  admin filters/details/export. No live production database used.
