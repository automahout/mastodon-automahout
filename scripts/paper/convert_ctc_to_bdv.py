#!/usr/bin/env python
"""Convert one CTC sequence (a folder of tTTT.tif) to a BigDataViewer HDF5 + XML for Mastodon.

usage: convert_ctc_to_bdv.py SEQ_DIR OUT_BASENAME VX VY VZ [--gzip]

Writes OUT_BASENAME.h5 and OUT_BASENAME.xml: one setup, one timepoint per frame, voxel size VX x VY x VZ in
micrometres, a resolution pyramid (x/y halved twice; z too for stacks thicker than 16 planes). Intensities
are copied unchanged (8-bit data widened to 16-bit; 16-bit stored as BDV reads it, unsigned). Run with uv:
uv run --project scripts/paper python scripts/paper/convert_ctc_to_bdv.py ...
"""
import os
import sys

import numpy as np
import npy2bdv
import tifffile


def main():
    seq, out, vx, vy, vz = sys.argv[1], sys.argv[2], *map(float, sys.argv[3:6])
    compression = "gzip" if "--gzip" in sys.argv[6:] else None  # 3D sequences: ~9 GB uncompressed
    frames = sorted(f for f in os.listdir(seq) if f.startswith("t") and f.endswith(".tif"))
    first = tifffile.imread(os.path.join(seq, frames[0]))
    is3d = first.ndim == 3
    nz = first.shape[0] if is3d else 1
    subsamp = ((1, 1, 1), (1, 2, 2), (1, 4, 4)) if nz <= 16 else ((1, 1, 1), (1, 2, 2), (2, 4, 4))
    blockdim = ((4, 256, 256),) * len(subsamp) if is3d else ((1, 256, 256),) * len(subsamp)
    # World coordinates in micrometres, as in the projects the pipeline was developed on: its distances
    # (displacement caps, radii) are physical.
    affine = np.array([[vx, 0, 0, 0], [0, vy, 0, 0], [0, 0, vz, 0]], dtype=float)
    w = npy2bdv.BdvWriter(out + ".h5", subsamp=subsamp, blockdim=blockdim, compression=compression,
                          overwrite=True)
    for t, f in enumerate(frames):
        if int(f[1:-4]) != t:
            raise SystemExit(f"frames are not numbered 0..n-1: {f} at position {t}")
        a = tifffile.imread(os.path.join(seq, f))
        if a.ndim == 2:
            a = a[np.newaxis]
        # BDV's HDF5 holds 16-bit data as int16 and reads it back as unsigned: store the uint16 bit pattern.
        w.append_view(a.astype(np.uint16).view(np.int16), time=t, voxel_size_xyz=(vx, vy, vz), voxel_units="µm",
                      m_affine=affine, name_affine="voxel size in µm")
    w.write_xml()
    w.close()
    print(f"{out}: {len(frames)} frames, {first.shape} {first.dtype}, voxel {vx} x {vy} x {vz} µm")


if __name__ == "__main__":
    main()
