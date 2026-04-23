---
description: One tick of repo babysitting. Implements an `ai-dev`-labelled issue if any remain, otherwise reviews an unreviewed PR, otherwise exits quietly. Designed for `/loop /babysit-repo`.
---

Arguments: $ARGUMENTS

Do **one** thing this tick, favouring quick wins, and exit. This skill is meant to be wrapped by `/loop` (e.g. `/loop 30m /babysit-repo` or self-paced `/loop /babysit-repo`), so every invocation must be idempotent and leave the repo in a reasonable state even if interrupted.

Repo: `soderbjorn/termtastic`. Work autonomously — do not ask for confirmation.

## 1. Parse arguments

`$ARGUMENTS` controls which tracks are eligible this tick. Recognised flags (case-insensitive, order-independent):

- `--reviews-only` / `reviews only` → consider PR reviews only; ignore issues even if PRs are idle.
- `--issues-only` / `issues only` → consider issue implementation only; ignore PRs.
- `--label <value>` → override the issue-track label filter (default `ai-dev`). Example: `--label bug`.
- `--any-label` → disable the issue-track label filter entirely.

Anything else in `$ARGUMENTS` is ignored at this layer — the delegated skill can interpret it if the format matches. If flags conflict (`--reviews-only --issues-only`), stop and report; do not guess.

Default when `$ARGUMENTS` is empty: both tracks eligible, `ai-dev` label filter on the issue track.

## 2. Fetch both candidate lists in parallel

Run both `gh` queries in the same tool-call block so the tick doesn't serialise two round-trips:

```
gh pr list --state open --repo soderbjorn/termtastic --json number,title,isDraft,latestReviews,comments --limit 200
gh issue list --state open --repo soderbjorn/termtastic --label <label> --json number,title,assignees --limit 200
```

(Skip the call that the flags have disabled.)

**PR candidate filter** (same rules as `/pick-review`):
- open and not a draft
- `latestReviews` empty
- no existing comment whose body contains the pick-review attribution footer (`posted by [Claude Code]` in a review context)

**Issue candidate filter** (same rules as `/pick-issue`):
- open
- not assigned to someone other than the current user (`@me`-assigned is still eligible)

## 3. Dispatch

Pick one action based on priority — **issues first**. When there is work waiting to be implemented, do that; only fall back to review when the backlog is empty. The user prefers backlog-burndown: a reviewed-but-unimplemented repo is worse than a reviewed-and-implemented one, so keep pulling issues through while they exist and let reviews queue up for the idle tail.

- **At least one issue candidate** → invoke the `/pick-issue` skill. Pass `$ARGUMENTS` through verbatim; `--label` and `--any-label` flags are recognised by `/pick-issue`. Report one sentence ("Implementing issue #N — <title>.") before the handoff.
- **No issue candidates, but ≥ 1 PR candidate** → invoke the `/pick-review` skill. Pass `$ARGUMENTS` through verbatim (the babysit flags above are no-ops to it, and it accepts an optional PR number or semantic hint). Report one sentence before the handoff ("Reviewing PR #N — <title>.") so the tick's intent is visible in the loop log.
- **Neither list has candidates** → print exactly one line of the form `Idle tick — 0 open \`ai-dev\` issues, 0 PRs awaiting review.` (update the label name and counts to match reality). Do not open a worktree, do not comment on anything, do not post a "nothing to do" message to GitHub. Exit the turn.

At most **one** action per tick — never chain an implementation into a review, even if both tracks have candidates. The loop will come back around. Because implementation turns take longer than reviews, the loop interval should be chosen with that in mind; a 30-minute cadence is fine, a 5-minute cadence likely isn't.

## 4. Respect tick boundaries

- Do not leave long-running background processes when exiting.
- Do not open a worktree unless the delegated skill actually needs one (`/pick-review` cleans up its own; `/pick-issue` deliberately keeps one, which is fine).
- If the delegated skill fails partway through (e.g. the build breaks in `/pick-issue`), do not attempt to recover in the same tick by switching tracks. Report the failure and exit; the loop will retry next tick.

## 5. Notes on the loop wrapper

The intended usage is one of:

- `/loop 30m /babysit-repo` — fixed 30-minute cadence. Good default for a repo with low-to-moderate activity.
- `/loop /babysit-repo` — self-paced. The model picks the next wake-up time based on how busy the repo looks. If this tick found work, a shorter delay (e.g. 10–15 min) is reasonable; if idle, go longer (45–60 min). Stay under the 5-minute cache-warm boundary only for active-work probes — for polling a human-authored repo, longer waits are correct.
- `/loop /babysit-repo --reviews-only` — run a review-only watchdog, leaving implementation work for manual invocation of `/pick-issue`.

Stopping the loop is the user's call (Ctrl-C or explicit stop). This skill does not self-terminate the loop.
