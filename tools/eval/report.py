#!/usr/bin/env python3
"""Tabulate tools/eval/results/*.jsonl side by side, one column per tag.

    tools/eval/report.py                 # everything in results/
    tools/eval/report.py gemini qwen3-4b # only these tags, in this order
"""
import glob, json, os, statistics, sys

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "results")


def load(tag, suite):
    p = os.path.join(RES, f"{tag}-{suite}.jsonl")
    if not os.path.exists(p):
        return []
    return [json.loads(l) for l in open(p) if l.strip()]


def mark(r):
    if r is None:
        return "  -  "
    if r.get("skipped"):
        return " skip"
    if r.get("failed") or r.get("error"):
        return " ERR "
    return "  ✓  " if r.get("pass") else "  ✗  "


def main(tags):
    if not tags:
        tags = sorted({os.path.basename(p).rsplit("-", 1)[0] for p in glob.glob(os.path.join(RES, "*.jsonl"))})
    w = max(12, max((len(t) for t in tags), default=12))

    print("TRIAGE (16 synthetic notifications; skip = ongoing, never reasoned about in production)")
    data = {t: {r["case"]: r for r in load(t, "triage")} for t in tags}
    print(f"{'case':<26}" + "".join(f"{t:>{w}}" for t in tags))
    for c in range(1, 17):
        label = next((d[c]["label"] for d in data.values() if c in d), "")
        row = f"{c:>2} {label:<23}"
        for t in tags:
            r = data[t].get(c)
            cell = mark(r) + (f"{r['action'][:9]:<9}" if r and not r.get("skipped") else " " * 9)
            row += f"{cell:>{w}}"
        print(row)
    for t in tags:
        rs = [r for r in data[t].values() if not r.get("skipped")]
        if not rs:
            continue
        ok = sum(1 for r in rs if r.get("pass"))
        fail = sum(1 for r in rs if r.get("failed"))
        ms = statistics.median(r["ms"] for r in rs)
        print(f"  {t}: {ok}/{len(rs)} correct, {fail} unparsable/failed, median {ms/1000:.1f} s per notification")

    print("\nCHAT (one turn per task; ✓ = expected tools called, forbidden ones not, args and answer as required)")
    data = {t: {r["task"]: r for r in load(t, "chat")} for t in tags}
    tasks = []
    for d in data.values():
        for k in d:
            if k not in tasks:
                tasks.append(k)
    print(f"{'task':<26}" + "".join(f"{t:>{w}}" for t in tags))
    for task in tasks:
        row = f"{task:<26}"
        for t in tags:
            r = data[t].get(task)
            cell = mark(r) + (f"{r['ms']/1000:5.1f}s" if r else "      ")
            row += f"{cell:>{w}}"
        print(row)
    for t in tags:
        rs = list(data[t].values())
        if not rs:
            continue
        ok = sum(1 for r in rs if r.get("pass"))
        err = sum(1 for r in rs if r.get("error"))
        ms = statistics.median(r["ms"] for r in rs)
        ft = statistics.median(r["firstTokenMs"] for r in rs if r.get("firstTokenMs", -1) >= 0) if any(r.get("firstTokenMs", -1) >= 0 for r in rs) else -1
        pt = sum(r.get("promptTokens", 0) for r in rs)
        ct = sum(r.get("completionTokens", 0) for r in rs)
        print(f"  {t}: {ok}/{len(rs)} pass, {err} errors, median {ms/1000:.1f} s per turn, median first token {ft/1000:.1f} s, "
              f"{pt} prompt + {ct} completion tokens over the run")

    if "-v" in sys.argv:
        print("\nFAILURES")
        for t in tags:
            for r in data[t].values():
                if not r.get("pass"):
                    why = []
                    if r.get("error"): why.append("error=" + str(r["error"])[:80])
                    if not r.get("expectOk"): why.append("expected tools missing")
                    if not r.get("forbidOk"): why.append("FORBIDDEN tool called")
                    if not r.get("argsOk"): why.append("args")
                    if not r.get("answerOk"): why.append("answer")
                    calls = [c["name"] for c in r.get("calls", [])]
                    print(f"  [{t}] {r['task']}: {', '.join(why)}; calls={calls}\n      answer: {r.get('answer','')[:200]!r}")


if __name__ == "__main__":
    main([a for a in sys.argv[1:] if not a.startswith("-")])
