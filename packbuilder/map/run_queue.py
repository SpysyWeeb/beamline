"""Run several build commands one after another (never two heavy builds at once).

Usage (from the repo root):
    .venv/map/Scripts/python packbuilder/map/run_queue.py QUEUE.txt [--after-file PATH]

QUEUE.txt holds one command per line (blank lines and lines starting with # are
skipped). Lines are read when they are reached, so commands can be appended
while the queue runs. With --after-file the queue first waits until that file
exists (for example the .build.json of a build already running). Each command's
output goes to data/map/logs/queue-<n>.out; a summary line per command is
appended to QUEUE.txt.done.
"""
import argparse
import shlex
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("queue")
    ap.add_argument("--after-file")
    a = ap.parse_args()
    q = Path(a.queue)
    done_log = q.with_name(q.name + ".done")
    if a.after_file:
        while not Path(a.after_file).exists():
            time.sleep(15)
    n = 0
    while True:
        lines = [l.strip() for l in q.read_text().splitlines() if l.strip() and not l.strip().startswith("#")]
        if n >= len(lines):
            break
        cmd = lines[n]
        n += 1
        out = REPO / "data" / "map" / "logs" / f"queue-{n}.out"
        t0 = time.time()
        args = shlex.split(cmd, posix=False)
        for cand in (REPO / args[0], REPO / (args[0] + ".exe")):  # Windows does not resolve a relative program against cwd
            if cand.exists():
                args[0] = str(cand)
                break
        with open(out, "w") as f:
            rc = subprocess.run(args, cwd=REPO, stdout=f, stderr=subprocess.STDOUT).returncode
        with open(done_log, "a") as f:
            f.write(f"{time.strftime('%H:%M:%S')} rc={rc} {round(time.time() - t0)}s {cmd}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
