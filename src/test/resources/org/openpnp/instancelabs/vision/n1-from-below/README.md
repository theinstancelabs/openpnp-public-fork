# N1 from below (BR-94), Process's parallax run p34/t2_px25

Process's run 11-03 17:44 (runs/p34/t2_px25, `tools/parallax.py`): N1 over the bottom camera at raw Z 19, 22 and 25, the
head at B, B+(5,0), B-(5,0), B+(0,5), B-(0,5) and B again (mm): the engine's N1 heights and step. Each file is the
first of the three captures at a point (`n1_<z>.00_<point>_0.png`, 1920 x 1080, the raw camera's orientation: Process's
tools undo OpenPnP's flip), converted to grey with OpenCV's BGR2GRAY (the engine's grey) and cropped to 352 x 352 px
from x 782, y 368: the engine's template window at the camera's pixel and its +-140 px search, and the high-pass blur's
reach (24 px) around them.

`tools/parallax_fit.py --half 8` on these single captures gives N1's k 17.9173, 16.6039, 15.4549 px/mm (k_result.json,
from all three captures: 17.9148, 16.6096, 15.4493). With that run's needle scales (k_result.json: 14.0871, 15.0422,
16.1427 at raw 36, 39, 42) parallax_k.py's formula gives K 65.131 mm, spread 0.098, f 674.7 px and the needle's 1.0166 mm
per raw mm (all three captures: 65.129, 0.087, 673.5, 1.0148).
