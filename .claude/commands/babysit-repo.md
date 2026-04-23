---
description: One tick of repo babysitting. Addresses an actionable owner comment on a Claude-authored PR if any remain, otherwise implements an `ai-dev`-labelled issue, otherwise reviews an unreviewed PR, otherwise exits quietly. Designed for `/loop /babysit-repo`.
---

Arguments: $ARGUMENTS

Do **one** thing this tick, favouring quick wins, and exit. This skill is meant to be wrapped by `/loop` (e.g. `/loop 30m /babysit-repo` or self-paced `/loop /babysit-repo`), so every invocation must be idempotent and leave the repo in a reasonable state even if interrupted.

Repo: `soderbjorn/termtastic`. Work autonomously — do not ask for confirmation.

## 1. Parse arguments

`$ARGUMENTS` controls which tracks are eligible this tick. Recognised flags (case-insensitive, order-independent):

- `--followups-only` / `followups only` → consider PR follow-ups only (actionable owner comments on Claude-authored PRs); ignore issues and reviews.
- `--reviews-only` / `reviews only` → consider PR reviews only; ignore follow-ups and issues.
- `--issues-only` / `issues only` → consider issue implementation only; ignore follow-ups and reviews.
- `--no-followups` → skip the follow-up track this tick but keep issues and reviews eligible.
- `--label <value>` → override the issue-track label filter (default `ai-dev`). Example: `--label bug`.
- `--any-label` → disable the issue-track label filter entirely.

Anything else in `$ARGUMENTS` is ignored at this layer — the delegated skill can interpret it if the format matches. If flags conflict (e.g. `--reviews-only --issues-only`, or any `--*-only` combined with another `--*-only`), stop and report; do not guess.

Default when `$ARGUMENTS` is empty: all three tracks eligible, `ai-dev` label filter on the issue track.

## 2. Fetch all candidate lists in parallel

Run the enabled `gh` queries in the same tool-call block so the tick doesn't serialise multiple round-trips:

```
gh pr list --state open --repo soderbjorn/termtastic --json number,title,body,author,isDraft,latestReviews,comments,createdAt,headRefName --limit 200
gh issue list --state open --repo soderbjorn/termtastic --label <label> --json number,title,assignees,createdAt --limit 200
```

(Skip the calls that the flags have disabled. The PR query feeds both the review track and the follow-up track — only one round-trip is needed.)

**PR-review candidate filter** (same rules as `/pick-review`):
- open and not a draft
- `latestReviews` empty
- no existing comment whose body contains the pick-review attribution footer (`posted by [Claude Code]` in a review context)

**Issue candidate filter** (same rules as `/pick-issue`):
- open
- not assigned to someone other than the current user (`@me`-assigned is still eligible)

**Follow-up candidate filter** (same rules as `/pick-followup`):
- PR is open (draft allowed), authored by Claude Code (PR body contains the `Generated with [Claude Code]` footer, or its linked issue has the `/pick-issue` attribution comment)
- has at least one comment from `soderbjorn` that is newer than the last commit on the PR's head branch, is actionable (imperative/request phrasing — use judgement), and has not already been answered by a Claude reply containing the canonical marker `addressed in commit <sha>`

Count-only is fine at this stage — the detailed per-comment analysis happens inside `/pick-followup`. For the follow-up count, a cheap approximation is acceptable: fetch `gh pr view <N> --json comments,commits` for the open PR list (run them in parallel) and count PRs where `soderbjorn` posted after the last commit. The delegated skill will confirm actionability when it runs.

## 3. Dispatch

Pick one action based on priority — **follow-ups first, then issues, then reviews**. The rationale:

1. **Follow-ups first.** If the owner has left feedback on a Claude-authored PR, closing that loop moves an existing PR toward merge. Leaving owner feedback unaddressed while Claude opens new PRs compounds the backlog.
2. **Then issues.** When no follow-ups are pending, keep burning down implementation work. A reviewed-but-unimplemented repo is worse than a reviewed-and-implemented one.
3. **Then reviews.** Reviews of others' PRs happen last; they queue up for the idle tail.

- **At least one follow-up candidate** (and the track isn't disabled by `--no-followups` / `--issues-only` / `--reviews-only`) → invoke the `/pick-followup` skill. Pass `$ARGUMENTS` through verbatim; follow-up-specific args (explicit PR number or semantic hint) are recognised by `/pick-followup`, and the babysit-only flags are no-ops to it. Report one sentence before the handoff ("Addressing follow-up on PR #N — <title>.").
- **No follow-ups, but ≥ 1 issue candidate** → invoke the `/pick-issue` skill. Pass `$ARGUMENTS` through verbatim; `--label` and `--any-label` flags are recognised by `/pick-issue`. Report one sentence ("Implementing issue #N — <title>.") before the handoff.
- **No follow-ups, no issues, but ≥ 1 PR-review candidate** → invoke the `/pick-review` skill. Pass `$ARGUMENTS` through verbatim (the babysit flags above are no-ops to it, and it accepts an optional PR number or semantic hint). Report one sentence before the handoff ("Reviewing PR #N — <title>.") so the tick's intent is visible in the loop log.
- **Nothing in any bucket** → print exactly one line of the form `Idle tick — 0 follow-ups, 0 open \`ai-dev\` issues, 0 PRs awaiting review.` (update the label name and counts to match reality). Do not open a worktree, do not comment on anything, do not post a "nothing to do" message to GitHub. Exit the turn.

At most **one** action per tick — never chain a follow-up into an implementation, or an implementation into a review, even if multiple tracks have candidates. The loop will come back around. Because follow-ups and implementations can take longer than reviews, the loop interval should be chosen with that in mind; a 15- or 30-minute cadence is fine, a 5-minute cadence likely isn't.

## 4. Respect tick boundaries

- Do not leave long-running background processes when exiting.
- Do not open a worktree unless the delegated skill actually needs one (`/pick-review` cleans up its own; `/pick-issue` and `/pick-followup` deliberately keep theirs, which is fine).
- If the delegated skill fails partway through (e.g. the build breaks in `/pick-issue` or `/pick-followup`), do not attempt to recover in the same tick by switching tracks. Report the failure and exit; the loop will retry next tick.

## 5. Notes on the loop wrapper

The intended usage is one of:

- `/loop 15m /babysit-repo` or `/loop 30m /babysit-repo` — fixed cadence. Good default for a repo with low-to-moderate activity; `/watch-repo` uses 15m by default.
- `/loop /babysit-repo` — self-paced. The model picks the next wake-up time based on how busy the repo looks. If this tick found work, a shorter delay (e.g. 10–15 min) is reasonable; if idle, go longer (45–60 min). Stay under the 5-minute cache-warm boundary only for active-work probes — for polling a human-authored repo, longer waits are correct.
- `/loop /babysit-repo --reviews-only` — run a review-only watchdog, leaving implementation and follow-up work for manual invocation.
- `/loop /babysit-repo --followups-only` — run a follow-up-only watchdog that only reacts to owner comments on Claude's PRs.

Three-track recap: each tick tries **follow-ups → issues → reviews** in that order and stops at the first track with a candidate, so a busy repo with lots of owner feedback will naturally prioritise closing out existing PRs before Claude opens new ones.

Stopping the loop is the user's call (Ctrl-C or explicit stop). This skill does not self-terminate the loop.
