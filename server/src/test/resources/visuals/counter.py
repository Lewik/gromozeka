"""Interactive Visual smoke-test handler. Stdout contains only full-section NDJSON updates."""
import json
import os
import sys
import time


def emit(sections):
    print(json.dumps(sections, ensure_ascii=False, separators=(",", ":")), flush=True)


data = {"count": 0, "busy": False, "pid": os.getpid(), "last-action": "ready", "last-query": ""}
emit({"data": data})
for line in sys.stdin:
    event = json.loads(line)
    button = event["button-id"]
    form = event["state"]["form"]
    data["last-action"] = button
    if button == "increment":
        data["count"] += int(form.get("amount", 1))
        data["last-query"] = form.get("query", "")
    elif button == "slow":
        data.update(busy=True, **{"last-action": "working"})
        emit({"data": data})
        time.sleep(0.75)
        data.update(busy=False, **{"last-action": "done"})
    elif button == "set-form":
        emit({"form": dict(form, query="script-value"), "data": data})
        continue
    elif button == "set-amount":
        emit({"form": dict(form, amount=5), "data": data})
        continue
    elif button == "bad-output":
        print("deliberately invalid output", flush=True)
        continue
    elif button == "oversized-output":
        print("x" * 9000, flush=True)
        continue
    elif button == "finish":
        data.update(busy=False, **{"last-action": "finished"})
        emit({"data": data})
        break
    emit({"data": data})
