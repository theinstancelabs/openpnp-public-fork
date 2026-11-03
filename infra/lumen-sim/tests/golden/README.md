# Golden excerpts

Text lines copied verbatim from `/home/lumen/instancelabs/runs/serial.log` (the real stock board, logged by
`tools/lumen.py` / `mc.py`). Format: `<epoch seconds> > sent line` / `<epoch seconds> < received line`.

The logger strips each received line (`readline().strip()`), so leading and trailing spaces are lost; the tests
compare stripped text. `> STOP` stands for the six lines `M410`, `M107 P3`, `M107 P2`, `M107 P1`, `M107 P0`,
`M18 B` written in one `write()`.

| File | serial.log lines | Shows |
|---|---|---|
| `m115.txt` | 1-39 | M115 (stock: `EMERGENCY_PARSER:0`, no `HOST_WATCHDOG`) |
| `m114.txt` | 40-42 | M114 with a G92 offset on X and Y |
| `m119.txt` | 43-48 | M119, all switches open |
| `m503.txt` | 49-74 | M503 (M204 S1000 in effect, M906 B200 = boot current) |
| `m211.txt` | 29361-29367 | M114 then M211 with X/Y workspace offsets |
| `m105.txt` | 123330-123331 | M105 |
| `b_moves.txt` | 3427-3469 | G92 B0 report, `G1 B1800 F21600` + M400 (5.39 s), `G1 B21600 F43200` + M400 (30.77 s), G4 busy lines |
| `g28_boot.txt` | 29116-29124 | G28 just after a power cycle (physical start unknown) |
| `g28_166293.txt` | 166291-166300 | G28 from X217.17 Y63.17 Z31.5 (native), M204 S1500: 5.80 s (a slow one: `homing_move_delay_s` 0.142) |
| `g28_166954.txt` | 166952-166966 | G28 from X240.94 Y215.88 Z31.5 (native), M204 S1500: 6.86 s, then M211 (slow too) |
| `g28_168535.txt` | 168526-168542 | M114 (counts X75048 Y63717: native X234.53 Y199.12), then G28 with M204 S1500: 5.25 s, 2 busy (the default speed; 169216 repeats it from the same start in 5.25 s) |
| `m410_lone.txt` | 122926-122940 | a lone M410 while idle: ok after 2.000 s; then the stop lines |
| `stop_burst.txt` | 96804-96814 | the six STOP lines in one write: first ok after 1.0 s |
| `m261_s1.txt` | 5494-5496 | `M261 A109 B3 S1` reply format |
| `m485.txt` | 96619-96621 | `M485` reply format and latency |
