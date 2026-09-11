# Titles read as log entries, because that is where they end up

The history here is written in a particular voice, and a PR title joins it. Read
`git log --format='%s' -25` before proposing anything: those are the examples that matter.

Ship's mechanics are fixed — the `feat: ` / `fix: ` prefix, three options, the operator's
answer final. This file constrains only the subject that follows the prefix.

## Rules

1. **Imperative mood, sentence case, no trailing period.** "Delete concurrently", not
   "Deleted concurrently" or "deletes concurrently".
2. **Say what the program now does, not what was edited.** The subject is a change in
   behaviour or structure, never a list of touched files or added symbols.
3. **Earn the second clause.** Most subjects here are one clause. A comma plus `since` /
   `and` / `rather than` is for when the reason or the consequence is the point — and then it
   carries the *why*, not a second mechanism.
4. **No ticket numbers, no module prefixes, no "WIP".** `:domain` and `:app:cli` belong in the
   body if they belong anywhere.
5. **Under ~70 characters** after the prefix, so the log stays readable at one glance.

## Worked examples

| | |
|---|---|
| ✅ | `feat: Rent a Mac by the hour, since two unknowns need one` |
| ❌ | `feat: Add macos-26 workflow_dispatch job` — names the mechanism, not the change |
| ✅ | `feat: Delete concurrently, since deletes are latency and not bytes` |
| ❌ | `feat: Parallelise deletion with a semaphore of 16` — the number will change; the reason will not |
| ✅ | `fix: Link with --as-needed: the binary would not start on Fedora` |
| ❌ | `fix: Fix linker flags` — says nothing a reader could not have guessed |
| ✅ | `feat: Store one viewing image per photo, not an original and a preview` |
| ❌ | `feat: Refactor derivative storage` — "refactor" hides whether behaviour moved |

The colon in `Link with --as-needed: the binary would not start on Fedora` is the one accepted
alternative to the comma, when the second half is the evidence rather than the reason.
