# The design is the authority, and nothing mechanical enforces it

`DESIGN.md` decides what this program does; the code is downstream of it. `README.md`'s
"What proves what" and "What the design turns on" are the short forms of the same claims.
Neither can be checked by a compiler, so it is checked here.

## The check

Read the diff being shipped.

1. **Behaviour DESIGN specifies.** For each behavioural change in the diff, find the section
   of `DESIGN.md` that specifies that behaviour (§1 constraints, §2 storage layout, §3 catalog,
   §4 sync, §5 derivatives, §6 app, §7 ingest CLI, §8 upload, §9 cost, §10 milestones). If the
   diff makes the section's text no longer true of the program, the section must change in the
   same diff.
2. **Claims in the README.** The bullets under "What proves what" and "What the design turns
   on" name the things a change is most likely to break quietly — the signer's vector suite,
   the truncated-LIST guard, existence being a `stat`, a missing directory deleting where an
   emptied one does not, `.photosignore` as the library marker, CR2 being carved, the SQL
   floor. If the diff changes what one of those claims describes, the claim must change too.
   The module table and the shipped-binary figures (size, glibc floor, dynamic library list)
   count as claims.

**Pass** when every such section and claim is either still true or updated in this same diff.

**Abort** otherwise, listing each one left behind: the section number or bullet, the claim it
makes, and the change in the diff that falsified it.

## What is not a failure

- A change with no behavioural surface — a rename, a test, a comment, a build-file tidy.
- A section that is *vaguer* than the code but not contradicted by it. DESIGN specifies
  intent, not every branch.
- A milestone in §10 that this change advances but does not complete.
