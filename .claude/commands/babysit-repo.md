---
description: One tick of repo babysitting, run in an isolated subagent context so `/loop /babysit-repo` doesn't accumulate per-tick transcripts. Processes every actionable owner comment on Claude-authored PRs, every unreviewed PR, and every `ai-dev`-labelled issue found at the start of the tick — each one in its own isolated sub-subagent.
---

Arguments: $ARGUMENTS

## Wrapper behaviour

This skill exists as a **thin delegator**: every invocation spawns a single
general-purpose subagent via the Agent tool and passes it the operational
instructions below. That outer subagent — the *orchestrator* — does
discovery once, then spawns one nested general-purpose subagent per
actionable candidate via the Agent tool, so each action runs in its own
isolated context. The parent session only ever sees the orchestrator's
final aggregated summary.

**Why isolate each tick.** When driven by `/loop /babysit-repo`, context
would otherwise pile up across ticks — each discovery pass, each
`/pick-*` invocation, all the `gh` output, all the diff reads. By
isolating each tick in a subagent (and each action in a sub-subagent),
the parent's transcript stays flat regardless of how long the loop runs,
the prompt cache stays warm around the loop's own overhead, and no
stale context from a prior tick or a prior action can leak into the
next decision.

**Why do everything per tick.** The earlier "one action per tick" model
forced backlog burndown to be paced by the loop interval — 10 open
items at a 30-minute cadence meant 5 hours to work through. With
per-action isolation, doing every candidate in a single tick is safe
(each action still has a fresh context) and drains the queue as fast
as the sub-skills can run. Priority order across categories is
preserved: follow-ups are handled before reviews, reviews before new
issue implementation, so owner feedback and mergeable PRs stay ahead
of new-PR creation even when the tick is multitasking.

## How to invoke the orchestrator

Make exactly one Agent tool call:

- `subagent_type`: `"general-purpose"`
- `description`: `"Babysit tick"`
- `prompt`: the entire block labelled **Orchestrator prompt** below,
  with every literal `$ARGUMENTS` replaced by the user's arguments
  string (empty string if no arguments were given). Pass the block
  verbatim otherwise — do not paraphrase, summarise, or restructure it.

Do **not** run any `gh`, `Read`, `Bash`, or `Skill` calls yourself
before or after the orchestrator call. Discovery, dispatch, and
aggregation are the orchestrator's job, not the parent's. The
parent's only responsibilities are:

1. Substitute `$ARGUMENTS` into the prompt.
2. Call Agent once with the substituted prompt.
3. When the orchestrator returns, print its summary verbatim (no
   prefix, no wrapping commentary, no re-explanation). That summary
   is the tick's entire output.

## Orchestrator prompt

```
You are the orchestrator subagent for one tick of the `/babysit-repo`
skill. You have no transcript from the parent session — that is
deliberate. Treat this prompt as your complete brief.

Arguments forwarded from the user: $ARGUMENTS

Repo: `soderbjorn/termtastic`. Work autonomously — do not ask for
confirmation. Your job is to discover every actionable candidate across
three tracks, then drive each one to completion by spawning a nested
general-purpose subagent per action via the Agent tool. Each nested
subagent runs one sub-skill (`/pick-followup`, `/pick-review`, or
`/pick-issue`) on one specific target and returns a short summary; you
aggregate those summaries into your final message to the parent.

### 1. Parse arguments

`$ARGUMENTS` controls which tracks are eligible this tick. Recognised
flags (case-insensitive, order-independent):

- `--followups-only` / `followups only` → consider PR follow-ups only.
- `--reviews-only` / `reviews only` → consider PR reviews only.
- `--issues-only` / `issues only` → consider issue implementation only.
- `--no-followups` → skip the follow-up track but keep issues and
  reviews eligible.
- `--label <value>` → override the issue-track label filter (default
  `ai-dev`). Example: `--label bug`.
- `--any-label` → disable the issue-track label filter entirely.

Anything else in `$ARGUMENTS` is ignored at this layer — the delegated
sub-skills can interpret target-specific hints (PR number, semantic
match) if the format matches, but because you always pass an explicit
target PR/issue number to each sub-skill, those hints will typically
be redundant here. Pass `$ARGUMENTS` through verbatim anyway so that
sub-skill-level flags like `--label` / `--any-label` still reach
`/pick-issue`.

If flags conflict (e.g. `--reviews-only --issues-only`, or any
`--*-only` combined with another `--*-only`), stop immediately and
return a one-sentence error summary; do not guess.

Default when `$ARGUMENTS` is empty: all three tracks eligible, `ai-dev`
label filter on the issue track.

### 2. Discover every candidate (one pass, parallel queries)

Run the enabled `gh` queries in the same tool-call block so discovery
doesn't serialise multiple round-trips:

```
gh pr list --state open --repo soderbjorn/termtastic --json number,title,body,author,isDraft,latestReviews,comments,createdAt,headRefName --limit 200
gh issue list --state open --repo soderbjorn/termtastic --label <label> --json number,title,assignees,createdAt --limit 200
```

Skip the calls that the flags have disabled. The PR query feeds both
the review track and the follow-up track — only one round-trip is
needed. For each open PR in the result, also fetch `gh pr view <N>
--json comments,commits` in parallel (one call per open PR, all in
the same tool-call block as each other) to surface the latest
`committedDate` on the head branch and the comment timestamps. Those
lookups feed both the re-review eligibility check and the follow-up
actionability approximation.

Classify every candidate into one of three lists — do not de-dupe
across lists; the same PR can legitimately land on both the review
and follow-up lists (though the dispatch order below means only the
follow-up action fires this tick for that PR).

**PR-review candidates** (same rules as `/pick-review`):
- open and not a draft
- `latestReviews` empty
- **Either** no existing comment whose body contains the pick-review
  attribution footer (`posted by [Claude Code]` in a review context),
  **or** the most recent such comment is older than the latest commit
  on the PR's head branch — i.e. new code has landed since Claude
  last reviewed (typically from a `/pick-followup` push), so the PR
  is eligible for a re-review. The re-review's own comment will be
  newer than the head commit, so the rule self-terminates.

**Issue candidates** (same rules as `/pick-issue`, plus a linked-PR
exclusion specific to babysit):
- open
- not assigned to someone other than the current user (`@me`-assigned
  is still eligible)
- **no open PR already linked to the issue via a closing keyword.**
  Parse each open PR's `body` for GitHub's closing keywords —
  case-insensitive `closes #N`, `close #N`, `closed #N`, `fixes #N`,
  `fix #N`, `fixed #N`, `resolves #N`, `resolve #N`, `resolved #N` —
  and subtract those issue numbers from the candidate set.

**Follow-up candidates** (same rules as `/pick-followup`):
- PR is open (draft allowed), authored by Claude Code (PR body
  contains the `Generated with [Claude Code]` footer, or its linked
  issue has the `/pick-issue` attribution comment)
- has at least one comment from `soderbjorn` that is newer than the
  last commit on the PR's head branch, is actionable
  (imperative/request phrasing — use judgement), and has not already
  been answered by a Claude reply containing the canonical marker
  `addressed in commit <sha>`

Count-only heuristics are fine at discovery — the delegated sub-skill
will confirm actionability when it runs. A cheap follow-up
approximation: count PRs where `soderbjorn` posted after the last
commit.

**If a PR is on both the follow-up and review lists, keep it on the
follow-up list only for this tick.** The follow-up push will move
the PR forward and typically land a new commit, at which point the
next tick's discovery will surface it for re-review naturally.
Double-dispatching in the same tick would review the PR before the
follow-up commit lands and waste work.

### 3. Dispatch every candidate, prioritised

Process the lists in this order — **all follow-ups first, then all
reviews, then all issues** — and within each list in the order
discovery returned them (which `gh ... list` gives oldest-first
already, so oldest candidates go first). The priority order matters
even when the tick is exhaustive: owner feedback on existing PRs
beats fresh reviews, and fresh reviews beat opening new PRs from
issues. Burning down the queue in that order keeps the mergeable
front of the backlog healthy before Claude adds new work to it.

For each candidate, spawn a **nested general-purpose subagent** via
the Agent tool. Each nested subagent:

- has `subagent_type` = `"general-purpose"`
- has a short description naming the track and target (e.g.
  `"Follow-up on PR #13"`, `"Review PR #42"`, `"Implement issue #27"`)
- has a `prompt` that instructs the nested subagent to invoke one
  specific sub-skill on one specific target via the Skill tool, then
  return a one-sentence summary

The nested subagent's prompt should look roughly like one of these:

- Follow-up: `You are a nested subagent. Invoke the "pick-followup"
  skill via the Skill tool with arguments "<PR number>
  $ARGUMENTS-rest" (where $ARGUMENTS-rest is the user's original
  arguments with any --*-only / --no-followups flags stripped, since
  this sub-skill has already been selected). When the skill finishes,
  return a single sentence summarising what happened — include the PR
  URL if one was produced, and note success or failure. Do not expand
  on it. Do not run additional tools after the skill returns.`
- Review: same shape, invoking `pick-review` with `"<PR number>
  $ARGUMENTS-rest"`.
- Issue: same shape, invoking `pick-issue` with `"<issue number>
  $ARGUMENTS-rest"`. `$ARGUMENTS-rest` for the issue track should
  preserve `--label` / `--any-label` if present.

Pass the sub-skill an explicit target number (the candidate's PR or
issue number) so it doesn't need to re-discover. The nested subagent's
CWD is inherited from you, which is inherited from the parent, so
project-level slash commands in `.claude/commands/` resolve.

Spawn the nested subagents **sequentially**, not in parallel — each
sub-skill mutates the repo (pushes commits, posts comments, opens PRs)
and parallel execution would race on the working tree, the GitHub API,
and on duplicate decisions. Wait for each nested subagent to return
before spawning the next.

After each nested subagent returns, record its one-sentence summary,
indexed by track and target, so you can assemble the aggregated
summary at the end. **Do not stop on failure.** If a sub-skill fails
(build break, merge conflict, network error, declined pick, etc.),
record the failure and move on to the next candidate. The loop's
next tick will retry the failed one if it's still a candidate then.

### 4. Respect tick boundaries

- Do not leave long-running background processes when exiting.
- Do not open a worktree yourself; each sub-skill manages its own.
  (`/pick-review` cleans up its own; `/pick-issue` and
  `/pick-followup` deliberately keep theirs, which is fine.)
- Do not re-query the candidate lists mid-tick. Snapshot at discovery;
  process the snapshot. A follow-up pushed this tick may make the
  same PR re-review-eligible — let the next tick see that.
- Do not chain across categories in a way that violates priority. If
  the follow-up list has items, drain it fully before starting the
  review list; drain the review list before the issue list.

### 5. Return value

Your final message to the parent must be a concise multi-line
summary, and nothing else. No headers, no prose commentary, no
re-explanation of the work — the parent prints it verbatim.

Format:

```
Babysit tick — <F> follow-up(s), <R> review(s), <I> issue(s) processed.
  • Follow-up PR #<n> — <one-line summary from nested subagent>
  • Review PR #<n> — <one-line summary from nested subagent>
  • Issue #<n> → PR #<m> — <one-line summary from nested subagent>
  ...
```

Include one bullet per action actually dispatched, in the order they
ran (follow-ups first, then reviews, then issues). If a nested
subagent reported failure, preserve that in the bullet ("… failed —
<reason>; loop will retry."). Each bullet should include a URL when
one was produced.

If nothing was dispatched at all:

```
Idle tick — 0 follow-ups, 0 PRs awaiting review, 0 open `<label>` issues.
```

(Substitute the effective label name — `ai-dev` by default, or the
value from `--label`, or `any` when `--any-label` is in effect.)

If arguments conflicted:

```
Conflicting flags in arguments: <detail>; no action taken.
```
```

## Notes on the loop wrapper

The intended usage is one of:

- `/loop 15m /babysit-repo` or `/loop 30m /babysit-repo` — fixed cadence.
  With the exhaustive-per-tick behaviour, a longer cadence (30–60m) is
  sensible for a busy repo: each tick may now run for a while as it
  drains the backlog, and over-frequent ticks would just no-op more
  often. `/watch-repo` uses 15m by default, which is still fine for a
  quiet repo.
- `/loop /babysit-repo` — self-paced. The model picks the next wake-up
  time based on how busy the repo looks. If this tick processed work,
  a shorter delay (e.g. 10–15 min) is reasonable; if idle, go longer
  (45–60 min). Stay under the 5-minute cache-warm boundary only for
  active-work probes — for polling a human-authored repo, longer waits
  are correct.
- `/loop /babysit-repo --reviews-only` — run a review-only watchdog,
  leaving implementation and follow-up work for manual invocation.
- `/loop /babysit-repo --followups-only` — run a follow-up-only
  watchdog that only reacts to owner comments on Claude's PRs.

Three-track recap: each tick drains **all follow-ups, then all reviews,
then all issues** in that order, so a busy repo with lots of open PRs
will naturally prioritise driving those to merge before Claude opens
new ones.

Stopping the loop is the user's call (Ctrl-C or explicit stop). This
skill does not self-terminate the loop.
