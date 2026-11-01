"""STATE.md: Holder parsing (fail closed), atomic edits, claim/release, the Deployed line."""
import os
import stat
import sys
import tempfile
import unittest

# il_deploy is one level up: also when unittest imports this module by bare name (no -t)
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import il_deploy  # noqa: E402
from fakes import STATE_MD  # noqa: E402


def holder(body):
    return il_deploy.parse_holder("# STATE\n\n## Machine\n" + body + "\n- **Air:** unknown.\n")


class HolderParsing(unittest.TestCase):
    IDLE = [
        "- **Holder:** nobody",
        "- **Holder:** Nobody",
        "- **Holder:** NOBODY.",
        "- **Holder:** nobody (released by Infra deploy at 19:40)",
        "- **Holder:** nobody, since 10-31 20:00",
        "- **Holder:** nobody; needle parked over the towel",
        "  - **Holder:** nobody",
        "-   **Holder:**   nobody   ",
        "- **Holder:**\n  nobody (released by Process at 18:00)",
        "- **Holder:** nobody (released by Process at 18:00,\n  valve off, needle parked)",
    ]
    HELD = [
        "- **Holder:** Process role, since 10-31 19:00 (bench tools)",
        "- **Holder:** none",
        "- **Holder:** free",
        "- **Holder:** Nobody?",
        "- **Holder:** nobody? (check with Process)",
        "- **Holder:** nobodyelse",
        "- **Holder:** nobody-ish",
        "- **Holder:** no body",
        "- **Holder:**",
        "- **Holder:** **nobody**",
        "- **Holder:** ~~Process~~ nobody",
        "- **Holder:** _nobody_",
        "- Holder: nobody",
        "- **Holder**: nobody",
        "* **Holder:** nobody",
        "**Holder:** nobody",
        "- **Holder:** nobody (Backend wants the machine at 20:00)",
        "- **Holder:** nobody\n  Backend wants ~10 min for a Z check",
        "- **Holder:** nobody\n  - UI wants 5 min",
        "- **Holder:** nobody (released by Process at 19:40)\n  - board 3 is still on the bed with wet paste; do not "
        "home over it\n  - nozzle N2 removed for cleaning",
        "- **Holder:** nobody\n  - re-home before any run",
        "- **Holder:** Infra (deploy il-build-3-abc1234) since 2026-10-31 20:15",
    ]

    def test_idle_variants(self):
        for body in self.IDLE:
            with self.subTest(body=body):
                h = holder(body)
                self.assertTrue(h.idle, h.reason)
                self.assertTrue(h.value.lower().startswith("nobody"))

    def test_held_variants(self):
        for body in self.HELD:
            with self.subTest(body=body):
                self.assertFalse(holder(body).idle)

    def test_no_holder_line(self):
        h = il_deploy.parse_holder("# STATE\n\n## Machine\n- **Air:** unknown.\n")
        self.assertFalse(h.idle)
        self.assertIn("no", h.reason.lower())

    def test_two_holder_lines(self):
        h = holder("- **Holder:** nobody\n- **Holder:** nobody")
        self.assertFalse(h.idle)
        self.assertIn("2", h.reason)

    def test_continuation_stops_at_next_bullet(self):
        h = il_deploy.parse_holder("- **Holder:** nobody\n- **Air:** the user wants 18 psi\n")
        self.assertTrue(h.idle)
        self.assertEqual(h.value, "nobody")

    def test_continuation_joined(self):
        h = holder("- **Holder:** Process role, since 10-31 19:00 (bench\n  tools; OpenPnP stopped)")
        self.assertEqual(h.value, "Process role, since 10-31 19:00 (bench tools; OpenPnP stopped)")

    def test_crlf(self):
        h = il_deploy.parse_holder("## Machine\r\n- **Holder:** nobody\r\n- **Air:** x\r\n")
        self.assertTrue(h.idle)
        self.assertEqual(h.value, "nobody")

    def test_the_real_shape_is_held(self):
        text = ("## Machine\n- **Holder:** Process role, since 10-31 19:00 (bench tools in `~/instancelabs/tools`, "
                "OpenPnP stopped; 27G verify +\n  weigh). **Paused 19:24: a person is at the machine.** Backend/Infra "
                "needing the machine:\n  add a line here (\"Backend wants ~10 min for X\").\n- **Air:** unknown.\n")
        self.assertFalse(il_deploy.parse_holder(text).idle)


class Replace(unittest.TestCase):
    def test_replace_holder_keeps_everything_else(self):
        text = STATE_MD.replace("- **Holder:** nobody (released by Process at 18:00)",
                                "- **Holder:** nobody (released by Process at 18:00,\n  needle parked)")
        new = il_deploy.replace_entry(text, "Holder", "Infra (deploy x) since 2026-10-31 20:15")
        self.assertIn("- **Holder:** Infra (deploy x) since 2026-10-31 20:15\n- **Air:**", new)
        self.assertNotIn("needle parked", new)
        self.assertEqual(new.replace("- **Holder:** Infra (deploy x) since 2026-10-31 20:15\n", ""),
                         text.replace("- **Holder:** nobody (released by Process at 18:00,\n  needle parked)\n", ""))

    def test_replace_keeps_indent_and_crlf(self):
        text = "## M\r\n  - **Holder:** nobody\r\n  - **Air:** x\r\n"
        new = il_deploy.replace_entry(text, "Holder", "Infra")
        self.assertEqual(new, "## M\r\n  - **Holder:** Infra\r\n  - **Air:** x\r\n")

    def test_replace_refuses_zero_or_two(self):
        with self.assertRaises(ValueError):
            il_deploy.replace_entry("## M\n- **Air:** x\n", "Holder", "Infra")
        with self.assertRaises(ValueError):
            il_deploy.replace_entry("- **Holder:** a\n- **Holder:** b\n", "Holder", "Infra")

    def test_deployed_line_replaced_with_continuation(self):
        new = il_deploy.set_deployed_line(STATE_MD, "il-build-3-abc1234 (abc1234) since 2026-10-31 20:15")
        self.assertIn("- **Deployed on the machine:** il-build-3-abc1234 (abc1234) since 2026-10-31 20:15\n\n## Owners",
                      new)
        self.assertNotIn("copied by hand", new)
        self.assertIn("- **Holder:** nobody (released by Process at 18:00)", new)

    def test_deployed_line_created_in_code_section(self):
        text = STATE_MD.replace("- **Deployed on the machine:** a scratch build (`realjar-sw8`),\n  copied by hand.\n",
                                "")
        new = il_deploy.set_deployed_line(text, "il-build-3-abc1234")
        code = new.split("## Code")[1].split("## Owners")[0]
        self.assertIn("- **Deployed on the machine:** il-build-3-abc1234\n", code)
        self.assertTrue(code.rstrip("\n").endswith("il-build-3-abc1234"))

    def test_deployed_line_created_with_section(self):
        new = il_deploy.set_deployed_line("# STATE\n\n## Machine\n- **Holder:** nobody\n", "il-build-3-abc1234")
        self.assertTrue(new.endswith("## Code\n- **Deployed on the machine:** il-build-3-abc1234\n"))
        self.assertIn("- **Holder:** nobody\n", new)


class AtomicEdit(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.path = os.path.join(self.dir, "STATE.md")
        with open(self.path, "w") as f:
            f.write(STATE_MD)
        os.chmod(self.path, 0o640)

    def tearDown(self):
        import shutil
        shutil.rmtree(self.dir)

    def read(self):
        with open(self.path) as f:
            return f.read()

    def test_edit_replaces_and_keeps_mode(self):
        il_deploy.edit_state_file(self.path, lambda t: t.replace("unknown", "18 psi"))
        self.assertIn("**Air:** 18 psi", self.read())
        self.assertEqual(stat.S_IMODE(os.stat(self.path).st_mode), 0o640)
        self.assertEqual(os.listdir(self.dir), ["STATE.md"])

    def test_concurrent_change_aborts(self):
        def someone_edits():
            with open(self.path, "a") as f:
                f.write("- a line another thread added\n")
        with self.assertRaises(il_deploy.StateFileConflict):
            il_deploy.edit_state_file(self.path, lambda t: t.replace("unknown", "18 psi"),
                                      before_rename=someone_edits)
        text = self.read()
        self.assertIn("a line another thread added", text)
        self.assertIn("**Air:** unknown", text)
        self.assertEqual(os.listdir(self.dir), ["STATE.md"])

    def test_concurrent_same_size_rewrite_aborts(self):
        def rewrite_same_size():
            with open(self.path, "w") as f:
                f.write(STATE_MD.replace("Process", "Backend"))
        with self.assertRaises(il_deploy.StateFileConflict):
            il_deploy.edit_state_file(self.path, lambda t: t + "x", before_rename=rewrite_same_size)

    def test_claim_and_release(self):
        prev = il_deploy.claim_holder(self.path, "Infra (deploy t) since 2026-10-31 20:15")
        self.assertEqual(prev, "nobody (released by Process at 18:00)")
        h = il_deploy.parse_holder(self.read())
        self.assertEqual(h.value, "Infra (deploy t) since 2026-10-31 20:15")
        self.assertFalse(h.idle)
        self.assertTrue(il_deploy.release_holder(self.path, "Infra (deploy t) since 2026-10-31 20:15",
                                                 "nobody (released by Infra deploy at 20:20)"))
        h = il_deploy.parse_holder(self.read())
        self.assertTrue(h.idle)
        self.assertEqual(h.value, "nobody (released by Infra deploy at 20:20)")

    def test_claim_refused_when_held(self):
        il_deploy.edit_state_file(self.path, lambda t: il_deploy.replace_entry(t, "Holder", "Process role"))
        before = self.read()
        with self.assertRaises(il_deploy.NotIdle):
            il_deploy.claim_holder(self.path, "Infra (deploy t) since 2026-10-31 20:15")
        self.assertEqual(self.read(), before)

    def test_release_leaves_someone_elses_holder(self):
        il_deploy.claim_holder(self.path, "Infra (deploy t) since 2026-10-31 20:15")
        il_deploy.edit_state_file(self.path, lambda t: il_deploy.replace_entry(t, "Holder", "Process role, 20:16"))
        self.assertFalse(il_deploy.release_holder(self.path, "Infra (deploy t) since 2026-10-31 20:15",
                                                  "nobody (released by Infra deploy at 20:20)"))
        self.assertEqual(il_deploy.parse_holder(self.read()).value, "Process role, 20:16")

    def test_release_keeps_a_request_added_under_the_claim(self):
        claim = "Infra (deploy t) since 2026-10-31 20:15"
        il_deploy.claim_holder(self.path, claim)
        il_deploy.edit_state_file(self.path, lambda t: t.replace(
            f"- **Holder:** {claim}\n", f"- **Holder:** {claim}\n  Backend wants ~10 min for a Z check.\n"))
        self.assertTrue(il_deploy.release_holder(self.path, claim, "nobody (released by Infra deploy at 20:20)"))
        text = self.read()
        self.assertIn("- **Holder:** nobody (released by Infra deploy at 20:20)\n  Backend wants ~10 min", text)
        self.assertFalse(il_deploy.parse_holder(text).idle, "the queued request still holds off other takers")

    def test_release_keeps_text_appended_to_the_claim_line(self):
        claim = "Infra (deploy t) since 2026-10-31 20:15"
        il_deploy.claim_holder(self.path, claim)
        il_deploy.edit_state_file(self.path, lambda t: t.replace(claim, claim + "; UI wants it next"))
        self.assertTrue(il_deploy.release_holder(self.path, claim, "nobody (released by Infra deploy at 20:20)"))
        self.assertEqual(il_deploy.parse_holder(self.read()).value,
                         "nobody (released by Infra deploy at 20:20); UI wants it next")

    def test_claim_keeps_the_previous_text_and_unclaim_restores_it_exactly(self):
        il_deploy.edit_state_file(self.path, lambda t: il_deploy.replace_entry(
            t, "Holder", "nobody (released by Process at 18:00,\n  valve off, needle parked)"))
        before = self.read()
        claim = "Infra (deploy t) since 2026-10-31 20:15"
        prev = il_deploy.claim_holder(self.path, claim)
        self.assertEqual(prev, "nobody (released by Process at 18:00,\n  valve off, needle parked)")
        self.assertIn(f"- **Holder:** {claim}\n- **Air:**", self.read())
        self.assertTrue(il_deploy.release_holder(self.path, claim, prev))
        self.assertEqual(self.read(), before)

    def test_released_value_carries_the_previous_text(self):
        prev = "nobody (released by Process at 18:00,\n  valve off, needle parked)"
        v = il_deploy.released_value("deploy", "20:20", None, prev)
        self.assertEqual(v, "nobody (released by Infra deploy at 20:20); earlier: (released by Process at 18:00, "
                            "valve off, needle parked)")
        self.assertTrue(il_deploy.parse_holder("- **Holder:** " + v).idle)
        # no growth: an earlier Infra stamp is replaced, the older text stays
        again = il_deploy.released_value("rollback", "21:00", "rolled back [x]", v)
        self.assertEqual(again, "nobody (released by Infra rollback at 21:00; rolled back [x]); earlier: (released "
                                "by Process at 18:00, valve off, needle parked)")
        self.assertEqual(il_deploy.released_value("deploy", "20:20", None, "nobody"),
                         "nobody (released by Infra deploy at 20:20)")

    def test_note_cannot_break_the_stamp(self):
        v = il_deploy.released_value("deploy", "20:20", "X failed (home: HTTP 409); wants? check", "nobody")
        self.assertNotIn("(home", v)
        h = il_deploy.parse_holder("- **Holder:** " + v)
        self.assertTrue(h.idle, h.reason)

    def test_release_that_cannot_be_written_raises(self):
        claim = "Infra (deploy t) since 2026-10-31 20:15"
        il_deploy.claim_holder(self.path, claim)

        def always():
            with open(self.path, "a") as f:
                f.write("- another edit\n")
        with self.assertRaises(il_deploy.StateFileConflict):
            il_deploy.release_holder(self.path, claim, "nobody", tries=2, before_rename=always)

    def test_release_retries_after_a_conflict(self):
        claim = "Infra (deploy t) since 2026-10-31 20:15"
        il_deploy.claim_holder(self.path, claim)
        calls = []

        def once():
            if not calls:
                calls.append(1)
                with open(self.path, "a") as f:
                    f.write("- late edit\n")
        self.assertTrue(il_deploy.release_holder(self.path, claim, "nobody (released by Infra deploy at 20:20)",
                                                 before_rename=once))
        text = self.read()
        self.assertIn("late edit", text)
        self.assertTrue(il_deploy.parse_holder(text).idle)


if __name__ == "__main__":
    unittest.main()
