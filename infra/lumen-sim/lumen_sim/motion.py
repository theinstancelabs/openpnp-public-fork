"""Planner blocks and the stepper: trapezoidal step timing, the 16-slot block ring, quick-stop, endstop hits.

Times are simulator seconds. Positions are integer step counts per axis (X Y Z A B). Blocks run rest to rest:
each starts and ends at Marlin's MINIMAL_STEP_RATE, which is how isolated moves (separated by G4/M400, as the
backend streams them) run on the board. Junction speeds between back-to-back blocks are not modelled.
"""
import math
from collections import deque

NAXES = 5
BLOCK_BUFFER_SIZE = 16          # ring of 16 = 15 usable blocks
FIRST_MOVE_DELAY = 0.100        # BLOCK_DELAY_FOR_1ST_MOVE, unless 3 blocks are queued first
MINIMAL_STEP_RATE = 120.0       # steps/s, planner.cpp
CLEANING_S = 1.0                # quick_stop(): cleaning_buffer_counter = TEMP_TIMER_FREQUENCY


class Trapezoid:
    """Step events completed vs time for n events: accelerate r0 -> rate, cruise, decelerate to rf."""

    def __init__(self, n, rate, accel, r0, rf):
        r0, rf = min(r0, rate), min(rf, rate)
        self.n, self.accel, self.r0, self.rf = n, accel, r0, rf
        s_acc = (rate * rate - r0 * r0) / (2 * accel)
        s_dec = (rate * rate - rf * rf) / (2 * accel)
        if s_acc + s_dec > n:  # never reaches the nominal rate
            peak2 = accel * n + (r0 * r0 + rf * rf) / 2
            rate = math.sqrt(peak2)
            s_acc = (peak2 - r0 * r0) / (2 * accel)
            s_dec = n - s_acc
        self.rate = rate
        self.s_acc, self.s_dec = s_acc, s_dec
        self.s_cru = n - s_acc - s_dec
        self.t_acc = (rate - r0) / accel
        self.t_cru = self.s_cru / rate
        self.t_dec = (rate - rf) / accel
        self.duration = self.t_acc + self.t_cru + self.t_dec

    def steps_at(self, t):
        if t <= 0:
            return 0.0
        if t < self.t_acc:
            return self.r0 * t + self.accel * t * t / 2
        t -= self.t_acc
        if t < self.t_cru:
            return self.s_acc + self.rate * t
        t -= self.t_cru
        if t < self.t_dec:
            return self.s_acc + self.s_cru + self.rate * t - self.accel * t * t / 2
        return float(self.n)

    def time_for_steps(self, k):
        if k <= 0:
            return 0.0
        if k >= self.n:
            return self.duration
        a, r0 = self.accel, self.r0
        if k <= self.s_acc:
            return (math.sqrt(r0 * r0 + 2 * a * k) - r0) / a
        if k <= self.s_acc + self.s_cru:
            return self.t_acc + (k - self.s_acc) / self.rate
        k -= self.s_acc + self.s_cru
        disc = max(0.0, self.rate * self.rate - 2 * a * k)
        return self.t_acc + self.t_cru + (self.rate - math.sqrt(disc)) / a


def trapezoid(n, rate, accel, r0=MINIMAL_STEP_RATE, rf=MINIMAL_STEP_RATE):
    return Trapezoid(n, rate, accel, r0, rf)


class Block:
    def __init__(self, end, delta, profile, extra_delay=0.0, line=None):
        self.end = list(end)          # planner step position after the block
        self.delta = list(delta)      # signed steps per axis
        self.n = max(abs(d) for d in delta)
        self.profile = profile
        self.extra_delay = extra_delay
        self.start = None             # stepper counts when it started
        self.t_start = None
        self.t_end = None
        self.cut = None               # step events at which an endstop ended it
        self.line = line              # the G-code line that queued it, when known

    def counts_after(self, events):
        events = max(0, min(self.n, events))
        out = []
        for s, d in zip(self.start, self.delta):
            k = (events * abs(d) + self.n // 2) // self.n  # Bresenham
            out.append(s + (k if d >= 0 else -k))
        return out


class Stepper:
    """The planner queue as the stepper ISR consumes it, evaluated lazily at any later time."""

    def __init__(self, counts, physical, steps_per_unit, switches=(0.0, 0.0, 0.0)):
        self.counts = list(counts)          # stepper position of the last finished step
        self.physical = physical            # Machine.physical, mm/deg
        self.switches = switches            # Machine.switches: physical X/Y/Z at which the min endstops trigger
        self.steps = steps_per_unit         # live list owned by the firmware (M92 edits it)
        self.last_end = -math.inf           # when the planner last ran out of blocks (end or quick-stop)
        self.blocks = deque()
        self.deliver_at = None              # first block's start when it was queued into an empty planner
        self.cleaning_until = -math.inf
        self.endstops_enabled = False
        self.endstop_hit = False            # latched like Endstops::hit_state
        self.freeze_at = None               # kill(): the stepper ISR stops at this time
        self.frozen = False
        self.on_move = None                 # on_move(start, line) after the head moved (a block ended or was cut):
        # start = physical X/Y/Z before, line = the block's G-code line
        self.limit_z = None                 # limit_z(z): where the arm's end stops hold Z (None: nothing does)

    # --- queries ---
    def update(self, now):
        if self.frozen:
            return
        if self.freeze_at is not None and now >= self.freeze_at:
            self._advance(self.freeze_at)
            self._cut(self.freeze_at)
            self.frozen = True
            return
        self._advance(now)

    def _advance(self, now):
        while self.blocks:
            b = self.blocks[0]
            if b.t_start is None:
                if self.deliver_at is None or now < self.deliver_at:
                    return
                self._start(b, self.deliver_at)
            if b.t_end > now:
                return
            self._finish(b)
            self.blocks.popleft()
            self.last_end = b.t_end
            if self.blocks:
                self._start(self.blocks[0], b.t_end)

    def busy(self, now):
        self.update(now)
        return bool(self.blocks) or now < self.cleaning_until

    def cleaning(self, now):
        return now < self.cleaning_until

    def full(self, now):
        self.update(now)  # a block that ended by now has freed its slot
        return len(self.blocks) >= BLOCK_BUFFER_SIZE - 1

    def moving(self, now):
        self.update(now)
        return bool(self.blocks) and self.blocks[0].t_start is not None and self.blocks[0].t_start <= now

    def next_event(self, now):
        if self.frozen or not self.blocks:
            return math.inf
        b = self.blocks[0]
        if b.t_start is None:
            return self.deliver_at if self.deliver_at is not None else math.inf
        return b.t_end

    def realtime_counts(self, now):
        self.update(now)
        if self.blocks and self.blocks[0].t_start is not None and not self.frozen:
            b = self.blocks[0]
            return b.counts_after(int(b.profile.steps_at(now - b.t_start) + 1e-9))
        return list(self.counts)

    def realtime_physical(self, now):
        counts = self.realtime_counts(now)
        out = [p + (c - k) / s for p, c, k, s in zip(self.physical, counts, self.counts, self.steps)]
        if self.limit_z:  # a block is straight: Z goes one way, so the stop holds it from there to the block's end
            out[2] = self.limit_z(out[2])
        return out

    # --- changes ---
    def add(self, block, now):
        if not self.blocks:
            self.deliver_at = now + FIRST_MOVE_DELAY + block.extra_delay
        self.blocks.append(block)
        if self.blocks[0].t_start is None and len(self.blocks) >= 3:
            self._start(self.blocks[0], now)

    def set_counts(self, counts):
        """stepper.set_position(): redefine the counts without moving (physical unchanged)."""
        self.counts = list(counts)

    def quick_stop(self, now):
        self.update(now)
        self._cut(now)
        self.cleaning_until = now + CLEANING_S

    def freeze(self, at):
        """Interrupts off (minkill): motion goes on until `at`, then nothing moves again."""
        self.freeze_at = at if self.freeze_at is None else min(at, self.freeze_at)

    def _cut(self, now):
        """Abort the running block where it is and drop the queue."""
        if self.blocks and self.blocks[0].t_start is not None:
            b = self.blocks[0]
            self._move_to(b.counts_after(int(b.profile.steps_at(now - b.t_start) + 1e-9)), b.line)
        if self.blocks:
            self.last_end = now
        self.blocks.clear()
        self.deliver_at = None

    def recheck_endstops(self, now):
        """The head or a switch moved under the running block (a physical fault): with the endstops on, its switch
        hit is worked out again, so the switch stops the head where it now triggers (at once if already passed)."""
        self.update(now)
        if self.frozen or not self.endstops_enabled or not self.blocks or self.blocks[0].t_start is None:
            return
        b = self.blocks[0]
        b.t_end, b.cut = b.t_start + b.profile.duration, None
        self._endstop_cut(b, int(b.profile.steps_at(now - b.t_start) + 1e-9))

    def _start(self, b, t):
        b.start = list(self.counts)
        b.t_start = t
        b.t_end = t + b.profile.duration
        if self.endstops_enabled:
            self._endstop_cut(b, 0)

    def _endstop_cut(self, b, done):
        """End block b at its first min-switch hit, at no fewer than `done` step events. self.physical is the head
        at the block's start (it changes only when a block ends, or under a fault)."""
        for axis in range(3):  # X Y Z min switches
            d = b.delta[axis]
            if d >= 0:
                continue
            k_axis = max(0, math.ceil((self.physical[axis] - self.switches[axis]) * self.steps[axis] - 1e-6))
            if k_axis > abs(d):
                continue
            events = max(done, 0 if k_axis == 0 else math.ceil(k_axis * b.n / abs(d)))
            t_hit = b.t_start + b.profile.time_for_steps(events)
            if t_hit <= b.t_end:
                b.t_end, b.cut = t_hit, events

    def _finish(self, b):
        if b.cut is not None:
            self._move_to(b.counts_after(b.cut), b.line)
            self.endstop_hit = True
        else:
            self._move_to(b.end, b.line)

    def _move_to(self, counts, line):
        start = self.physical[:3]
        for i in range(NAXES):
            self.physical[i] += (counts[i] - self.counts[i]) / self.steps[i]
        if self.limit_z:  # steps past an end stop are lost: the counts go on, the head does not
            self.physical[2] = self.limit_z(self.physical[2])
        self.counts = list(counts)
        if self.on_move:
            self.on_move(start, line)
