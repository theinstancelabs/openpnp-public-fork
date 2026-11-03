# Real top-camera frames for BareSpotCheckTest

Taken on the real Lumen on 2026-10-29 between 00:00 and 00:20, during the first real-machine session of
automatic registration (software thread session 2). Camera controls as OpenPnP set them: white balance
2800 K manual, exposure 146, ring light on.

Each file is the frame cut to a square of +-3.5 mm around the camera's centre, at full resolution, saved
as JPEG quality 90 (so the spot under test is the cut's centre):

- bench frames (`snap.sh`, raw, not flipped): 0.01074 x 0.01106 mm/px, 652 x 632 px;
- `*_openpnp.jpg` (OpenPnP's `captureTransformed`, turned by 180 degrees as the engine sees it, kept that
  way): 0.01068 x 0.01073 mm/px, 656 x 652 px.

The source frames are in `runs/sw_session2/looks/` (same name, `.jpg` or `.png`), with
`looks/labels.json`. Label = what is inside the 1.0 mm disc at the centre, judged by eye. The 19 labelled
frames, plus the corner frame of the first board probe.

| file | source | label |
|---|---|---|
| blank_310_247.jpg | blank_310_247.jpg | bare (blank) |
| blank_322_249.5.jpg | blank_322_249.5.jpg | bare (blank) |
| blank_328_245.5.jpg | blank_328_245.5.jpg | bare (blank in the dark zone of the ring light; the ring median read 97 %) |
| bare_blank_324_245.5_bench.jpg | bare_blank_324_245.5_bench.jpg | bare (same; 90 %) |
| bare_blank_324_245.5_openpnp.jpg | bare_blank_324_245.5_openpnp.png | bare (the same spot through OpenPnP; 89 %) |
| bare_blank_300.32_245.5_bench.jpg | bare_blank_300.32_245.5_bench.jpg | bare (N1 touched here; the blank was loose and bowed) |
| paste_298.647_240.218.jpg | paste_298.647_240.218.jpg | bare (paste dots and dropped parts 2-4 mm away) |
| paste_305.389_240.371.jpg | paste_305.389_240.371.jpg | bare (paste dots and parts nearby) |
| paste_318.473_240.454.jpg | paste_318.473_240.454.jpg | bare (paste dots nearby) |
| b3_mask_67_67.jpg | b3_mask_67_67.jpg | bare (board 3 mask, trace nearby) |
| b3_mask_8_67.jpg | b3_mask_8_67.jpg | bare (board 3 mask) |
| b3_R1_37.5_42.5.jpg | b3_R1_37.5_42.5.jpg | bare (board 3 mask inside the R1-R8 ring; 60 %) |
| b3_mask_37_37.jpg | b3_mask_37_37.jpg | bare (board 3 mask, resistors ~2 mm away, a 0.13 mm scratch across the disc; 54 %) |
| centre_c1_304.11_238.9.jpg | centre_c1_304.11_238.9.jpg | paste (dot in the disc) |
| centre_c2_314.54_239.47.jpg | centre_c2_314.54_239.47.jpg | paste (dot in the disc) |
| centre_c3_297.62_234.34.jpg | centre_c3_297.62_234.34.jpg | paste (smeared paste in the disc) |
| centre_c4_301.94_238.44.jpg | centre_c4_301.94_238.44.jpg | paste (dot in the disc) |
| b3_mask_8_8.jpg | b3_mask_8_8.jpg | hole (mounting-hole copper ring in the disc) |
| b3_mask_67_8.jpg | b3_mask_67_8.jpg | edge (board edge across the disc) |
| b3reg_corner_2_2_alt_openpnp.jpg | b3reg_corner_2_2_alt_openpnp.png | hole (alternate (3, 3.73) of the corner target (2, 2) of the first board probe: ftp's mounting hole in the disc; not in labels.json, labelled here from the frame) |

The percentages are the foreign share the first BareSpotCheck (ring median, colourDelta 20) gave the
bare spots it refused.
