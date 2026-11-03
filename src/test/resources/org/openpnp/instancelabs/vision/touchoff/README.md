# Real touch-off frames for TouchOffRealFrameTest (F9)

The second needle touch-off of real-machine session 4 (2026-10-29 17:37, jar a4bb6d20d): 10 half-turn dots on the
blank at commanded gaps 0.45, 0.40, ... 0.00 mm, 1.6 mm apart along +X. Every dot formed, but thin paste strings
joined them into one blob, which the size and circularity filters dropped: the engine read diameters 0.373 mm, then
"no dot" 9 times, and concluded "dot stopped forming between 0.450 and 0.400 mm" (it would have raised the needle
about 0.4 mm; not applied). Sources: `runs/sw_session4/s7_after_touchoff2.png` and `runs/sw_session4/ladder2_290.jpg`.
Cropped, saved as JPEG quality 90 (4:4:4).

| file | what it is |
|---|---|
| touchoff2_openpnp_after.jpg | OpenPnP's top-camera frame right after the run, over the last dot: rows 380-739, columns 0-1119 of the 1920 x 1080 frame, 0.01068 x 0.01073 mm/px, OpenPnP's orientation (+X right, +Y up). Dots 3 (gap 0.30) to 9 (gap 0.00); the full frame's centre, crop pixel (960, 160), is the spot of dot 9. |
| touchoff2_bench290_after.jpg | The bench camera's frame (`snap.sh`) centred at X 290, taken later that evening, turned by 180 degrees into OpenPnP's orientation: rows 400-759, columns 450-1249, 0.01074 x 0.01106 mm/px. Dots 0 (gap 0.45, two droplets) to 3; the spots of dots 0 to 3 lie at crop pixel x 193 + 149 j (X 286.6 + 1.6 j, from dot 9 at X 300.9 in the bench frame at X 297.5), on the dots' row. |

Neither run kept the frames from before the dots: the test makes each "before" by blending the bare rows just above and
below the ladder over its band (the ladder is the only paste in that band).
