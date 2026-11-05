# The needle's end from below (BR-94), np1.py's frames

Process's NP-1 (11-04 23:44:17, runs/p37/NOTES.md "NP"): the 25G plastic tapered needle, primed and clean from below,
at raw Z 40.4 over the bottom camera; the head at B, B+(1,0), B-(1,0), B+(0,1), B-(0,1) and B again (mm), each
approached from (-0.5, -0.5). The frames are `runs/p37/np/np1_<k>_<dx>_<dy>.jpg` (1920 x 1080, the raw camera's
orientation: Process's tools undo OpenPnP's flip), cropped here to 600 x 600 px from x 657, y 244 (around (957, 544))
and saved as PNG from the JPEG as OpenCV decodes it.

np1.py analysed the live frames before it saved them as JPEG (quality 95): on these files its spot() and fit give the
end at (956.864, 543.202) px (np1.json: 956.87, 543.20), M [[15.412, 0.171], [-0.072, 15.622]] px/mm (np1.json
[[15.414, 0.133], [-0.078, 15.632]]), residual 0.150 px (0.142), drift 0.149 px (0.15), peaks 140, 149, 131, 132,
136, 143 (138 for the fifth). Process's BC (957.5, 544.2) and OFF0 (19.77, -72.184) give OFF_true (19.729, -72.248).
