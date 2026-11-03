"""Load the serial.log excerpts in tests/golden/."""
import os

DIR = os.path.join(os.path.dirname(__file__), "golden")


def load(name):
    """[(epoch, '>' or '<', stripped text)] for tests/golden/<name>.txt"""
    out = []
    with open(os.path.join(DIR, name + ".txt"), encoding="utf-8") as f:
        for raw in f:
            t, d, text = raw.rstrip("\n").split(" ", 2)
            out.append((float(t), d, text.strip()))
    return out


def replies(rows, sent):
    """Received lines that follow the first '> sent' row, up to and including the next 'ok'."""
    i = next(k for k, r in enumerate(rows) if r[1] == ">" and r[2] == sent)
    t0, got = rows[i][0], []
    for t, d, text in rows[i + 1:]:
        if d != "<":
            continue
        got.append((round(t - t0, 3), text))
        if text.startswith("ok"):
            break
    return got
