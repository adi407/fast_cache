<!--
Thanks for sending this. Nothing below is bureaucracy — each line exists because skipping it has cost us
real time before. Delete any section that genuinely doesn't apply.
-->

## What changes, and why

<!--
The *why* is the part a future reader cannot reconstruct. "Fixes #12" tells them nothing about the
trade-off you made or the alternative you rejected.
-->

## How it was verified

<!--
Which tests you added, and what fails without the change. For a bug fix, the new test should fail on main.
If you measured something, put the numbers here.
-->

## Checklist

- [ ] A test fails without this change
- [ ] `mvn -B verify` passes locally
- [ ] `pytest -q` passes locally (from `python/`, with the engine JAR in `fastcache_ai/_bin/`)
- [ ] No new `synchronized` — virtual threads pin their carrier inside a monitor
- [ ] No new runtime dependency in `fastcache-engine` (it ships inside a Python wheel)
- [ ] If the wire protocol or TTL grammar changed, **both** the Java and Python sides were updated
- [ ] If behaviour described in `ARCHITECTURE.md` changed, that document was updated too

## Anything you're unsure about

<!--
Genuinely useful. A reviewer would much rather hear "I wasn't sure whether the lease should be held across
the write" than discover the question themselves three months later.
-->
