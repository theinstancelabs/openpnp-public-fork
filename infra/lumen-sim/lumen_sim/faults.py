"""Faults on the 'ok' replies: drop, delay or duplicate the ok of chosen host lines.

A rule picks lines by `nth` (the nth line the controller reads after the rule is set, 1 = the next one), by
`match` (a regex searched in the line), or every line; `count` limits how many lines it hits (default: 1 for nth,
unlimited otherwise). With nth, the count lines in a row from the nth one.
"""
import math
import random
import re

OK_FAULTS = ("no_ok", "delay_ok", "dup_ok")
# the keys each ok fault takes: any other key (a misspelt "Nth" or "line") is refused, not read as "every line"
SELECTORS = ("fault", "nth", "match", "count")
KEYS = {"no_ok": SELECTORS, "dup_ok": SELECTORS, "delay_ok": SELECTORS + ("s", "random")}


def seconds(value, name):
    """A finite number >= 0 (JSON allows NaN and Infinity; a bool is not a number here), else ValueError."""
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
        raise ValueError("%s must be a finite number >= 0, not %r" % (name, value))
    return float(value)


def count(value, name):
    if isinstance(value, bool) or not isinstance(value, int) or value < 1:
        raise ValueError("%s must be an integer >= 1, not %r" % (name, value))
    return value


class Rule:
    """One ok fault; the spec is checked here, so a bad one is refused before it can fail on a later line."""

    def __init__(self, spec, lines_seen):
        self.kind = spec["fault"]
        extra = sorted(set(spec) - set(KEYS[self.kind]))
        if extra:
            raise ValueError("%s takes %s, not %s" % (self.kind, ", ".join(KEYS[self.kind][1:]), ", ".join(extra)))
        self.spec = dict(spec)
        self.nth = None if spec.get("nth") is None else count(spec["nth"], "nth")
        match = spec.get("match")
        if match is not None and not isinstance(match, str):
            raise ValueError("match must be a regex string, not %r" % (match,))
        try:
            self.match = re.compile(match) if match else None
        except re.error as e:  # not a ValueError
            raise ValueError("bad match regex %r: %s" % (match, e)) from None
        self.left = count(spec["count"], "count") if spec.get("count") is not None else (
            1 if self.nth is not None else None)
        self.base = lines_seen
        self.span = self.left  # nth: this many lines in a row
        self.targets = set()
        self.delay = self.random = None
        if self.kind == "delay_ok":
            rnd = spec.get("random")
            if rnd is not None:
                if not isinstance(rnd, (list, tuple)) or len(rnd) != 2:
                    raise ValueError("random must be [low, high], not %r" % (rnd,))
                self.random = (seconds(rnd[0], "random low"), seconds(rnd[1], "random high"))
                if self.random[0] > self.random[1]:
                    raise ValueError("random must be [low, high], not %r" % (rnd,))
            elif "s" not in spec:
                raise ValueError("delay_ok needs s (seconds) or random [low, high]")
            else:
                self.delay = seconds(spec["s"], "s")

    def observe(self, seq, text, lines_seen):
        if self.left is not None and self.left <= len(self.targets):
            return
        if self.nth is not None:
            hit = 0 <= lines_seen - self.base - self.nth < self.span
        elif self.match is not None:
            hit = self.match.search(text) is not None
        else:
            hit = True
        if hit:
            self.targets.add(seq)

    def describe(self):
        return dict(self.spec, pending=len(self.targets))


class Faults:
    def __init__(self, seed=0):
        self.rng = random.Random(seed)
        self.rules = []
        self.lines_seen = 0

    def add(self, spec):
        rule = Rule(spec, self.lines_seen)
        self.rules.append(rule)
        return rule

    def clear(self):
        self.rules = []

    def on_line(self, seq, text):
        self.lines_seen += 1
        for r in self.rules:
            r.observe(seq, text, self.lines_seen)

    def filter(self, data, kind, seq):
        """[(extra delay s, bytes)] to send for one firmware output."""
        if kind != "ok" or seq is None:
            return [(0.0, data)]
        out = [(0.0, data)]
        dropped = False
        for r in list(self.rules):  # every rule on this line is used up, also behind a no_ok
            if seq not in r.targets:
                continue
            r.targets.discard(seq)
            if r.left is not None:
                r.left -= 1
                if r.left <= 0 and not r.targets:
                    self.rules.remove(r)
            if r.kind == "no_ok":
                dropped = True
            elif r.kind == "delay_ok":
                extra = self.rng.uniform(*r.random) if r.random else r.delay
                out = [(d + extra, b) for d, b in out]
            elif r.kind == "dup_ok":
                out = out + [out[-1]]
        return [] if dropped else out

    def describe(self):
        return [r.describe() for r in self.rules]
