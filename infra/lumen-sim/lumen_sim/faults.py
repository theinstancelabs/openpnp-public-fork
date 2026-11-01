"""Faults on the 'ok' replies: drop, delay or duplicate the ok of chosen host lines.

A rule picks lines by `nth` (the nth line the controller reads after the rule is set, 1 = the next one), by
`match` (a regex searched in the line), or every line; `count` limits how many lines it hits (default: 1 for nth,
unlimited otherwise).
"""
import random
import re

OK_FAULTS = ("no_ok", "delay_ok", "dup_ok")


class Rule:
    def __init__(self, spec, lines_seen):
        self.kind = spec["fault"]
        self.spec = dict(spec)
        self.nth = spec.get("nth")
        self.match = re.compile(spec["match"]) if spec.get("match") else None
        if self.nth is not None and (not isinstance(self.nth, int) or self.nth < 1):
            raise ValueError("nth must be an integer >= 1")
        self.left = spec.get("count", 1 if self.nth is not None else None)
        self.base = lines_seen
        self.targets = set()
        if self.kind == "delay_ok":
            rnd = spec.get("random")
            if rnd is not None and (len(rnd) != 2 or rnd[0] > rnd[1]):
                raise ValueError("random must be [low, high]")
            if rnd is None and not isinstance(spec.get("s"), (int, float)):
                raise ValueError("delay_ok needs s (seconds) or random [low, high]")

    def observe(self, seq, text, lines_seen):
        if self.left is not None and self.left <= len(self.targets):
            return
        if self.nth is not None:
            hit = lines_seen - self.base == self.nth
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
        for r in list(self.rules):
            if seq not in r.targets:
                continue
            r.targets.discard(seq)
            if r.left is not None:
                r.left -= 1
                if r.left <= 0 and not r.targets:
                    self.rules.remove(r)
            if r.kind == "no_ok":
                return []
            if r.kind == "delay_ok":
                lo_hi = r.spec.get("random")
                extra = self.rng.uniform(*lo_hi) if lo_hi else float(r.spec["s"])
                out = [(d + extra, b) for d, b in out]
            elif r.kind == "dup_ok":
                out = out + [out[-1]]
        return out

    def describe(self):
        return [r.describe() for r in self.rules]
