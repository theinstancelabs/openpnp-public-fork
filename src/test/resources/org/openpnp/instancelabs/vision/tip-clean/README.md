# Is the needle's end clean? (BR-94), tipclean.py's frames

Process's `runs/p37/bin/tipclean.py --selftest` (runs/p37/tipclean.md, 11-05) on 26 real bottom-camera frames, plus
S1a/S2/S3's (runs/p38/s1|s2|s3/np/np1_0_+0_+0.jpg, k from their np1.json). Every frame is 1920 x 1080 in the raw
camera's orientation (Process's tools undo OpenPnP's flip) and the check reads only two parts of it, so each is kept as:

- `<case>.png`: the end, 160 x 160 px from x 877, y 464 (Process's BC (957.5, 544.2) +- 80 px: the end search reaches
  2 mm = 32.6 px at k 16.3, the annulus 1 mm more, and the top-hat 8 px more);
- `<case>_rim.png`: the ring's glow at the frame's edges (rows 340-739, columns 0-139 and 1780-1919) as 10 x 10 px block
  means, left strip then right strip (28 x 40 px): the light check reads only their mean colour.

On the frames rebuilt from these (black elsewhere) tipclean.py's check() gives the same verdicts, peaks, spreads and reds
(to 0.0015) as on the full frames, and the rim's grey to 0.3. Sources: np1_* `p37/np/np1_<i>_*.jpg`, np1after_*
`p37/np/after/`, t1/t4/t6/t7_below_* `p34/*.png`, px25_nd* `p34/t2_px25/nd_<z>_c0_0.png`, a1_tip_z31.5 `p32/`,
b0_nd27pose_31.5 `p29/`, n1_from_below `p37/np/`, p38_s* `p38/s*/np/np1_0_+0_+0.jpg`.
