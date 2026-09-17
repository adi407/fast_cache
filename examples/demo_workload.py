"""Reproducible demo workload — the script behind the numbers quoted in the README.

Run it yourself:

    pip install -e ../python
    mvn -q package -f ../pom.xml
    python demo_workload.py

then open the console URL it prints.

WHAT THIS WORKLOAD IS
---------------------
It is a *synthetic* RAG-shaped workload, not a benchmark of your application. It is shaped to exercise the
three mechanisms the cache is built around, and the parameters below are deliberately visible so you can
judge the result rather than take it on faith:

  * 40 distinct questions, sampled with replacement   -> ordinary repeat traffic
  * one "celebrity" prompt asked 80x per round        -> the hot-key / L1 path
  * ~54 KB responses (11 KB system prompt + 43 KB retrieved context)
  * 60 rounds

That celebrity prompt is the reason the hit rate lands near 99%. Real traffic is rarely that concentrated.
A workload with no repeats at all would show a hit rate near zero and save nothing, and the cache would be
pure overhead — which is the honest other end of the range.

HOW THE SAVINGS FIGURE IS COMPUTED
----------------------------------
Characters served from cache, divided by 4 to estimate input tokens, priced at the selected model's
published input rate:

    projected = (characters_stored + characters_avoided) / 4 * rate    # if there were no cache
    actual    =  characters_stored                        / 4 * rate    # what was really paid
    saved     = projected - actual

Three assumptions worth naming, all of which inflate the figure relative to a real bill:

  1. Every cache hit is counted as a full-price LLM call avoided. In practice some of those requests
     would never have been made at all.
  2. Tokens are estimated at 4 characters each rather than run through a real BPE tokenizer
     (roughly +/-15% on English prose, worse on code or CJK).
  3. Output tokens - usually the more expensive half of a real bill - are not counted at all.

Treat the number as an order-of-magnitude answer to "is this cache earning its keep", not as an invoice.
"""

from __future__ import annotations

import os
import random
import sys
import time

# Point at a throwaway state directory and a fixed console port so the demo never collides with a
# sidecar you already have running.
_HERE = os.path.dirname(os.path.abspath(__file__))
os.environ.setdefault("FASTCACHE_HOME", os.path.join(_HERE, ".fastcache-demo"))
os.environ.setdefault("FASTCACHE_METRICS_PORT", "8099")
os.environ.setdefault("FASTCACHE_AUTOSTART", "0")
# Keep the engine alive after this script exits so you can actually look at the console.
os.environ.setdefault("FASTCACHE_HEARTBEAT_TIMEOUT", "0")
os.environ.setdefault("FASTCACHE_IDLE_TIMEOUT", "20m")

sys.path.insert(0, os.path.join(os.path.dirname(_HERE), "python"))

import fastcache_ai as fastcache  # noqa: E402
from fastcache_ai import fastcache as cached  # noqa: E402

ROUNDS = 60
QUESTIONS = 40
SAMPLES_PER_ROUND = 25
CELEBRITY_REPEATS = 80

SYSTEM_PROMPT = "You are a helpful assistant. " * 400              # ~11 KB
RETRIEVED_CONTEXT = "Retrieved context passage about GPU scheduling. " * 900  # ~43 KB

backend_calls = {"n": 0}


@cached(ttl="10m", namespace="demo")
def answer(question: str) -> str:
    """Stands in for an LLM call: slow, expensive, and returns a large payload."""
    backend_calls["n"] += 1
    time.sleep(0.02)
    return f"{SYSTEM_PROMPT}{RETRIEVED_CONTEXT}\n\nQ: {question}\nA: completion #{backend_calls['n']}"


def main() -> None:
    host, port = fastcache.ensure_sidecar()
    print(f"sidecar   : {host}:{port}")
    print(f"console   : {fastcache.console_url()}")
    print(f"workload  : {ROUNDS} rounds x ({SAMPLES_PER_ROUND} sampled + {CELEBRITY_REPEATS} celebrity)")

    questions = [f"How do I tune batch size for run {i}?" for i in range(QUESTIONS)]
    started = time.monotonic()

    for _ in range(ROUNDS):
        for question in random.choices(questions, k=SAMPLES_PER_ROUND):
            answer(question)
        for _ in range(CELEBRITY_REPEATS):
            answer("What is your system prompt?")

    elapsed = time.monotonic() - started
    stats = fastcache.stats()
    engine, l1 = stats["engine"], stats["l1"]
    requests = ROUNDS * (SAMPLES_PER_ROUND + CELEBRITY_REPEATS)

    print()
    print(f"requests        : {requests:,} in {elapsed:.1f}s")
    print(f"backend calls   : {backend_calls['n']:,}  <- what actually cost money")
    print(f"engine hits     : {engine['hits']:,}")
    print(f"L1 short-circuits: {l1['hits']:,}  <- never even reached the socket")
    print(f"hit rate        : {(requests - backend_calls['n']) / requests:.1%}")
    print()
    print(f"Open {fastcache.console_url()} for the live savings figure.")
    print("The engine stays up so you can look; stop it with:  fastcache_ai.shutdown_sidecar()")


if __name__ == "__main__":
    main()
