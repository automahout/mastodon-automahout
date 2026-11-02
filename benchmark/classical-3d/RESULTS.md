# Does the classical anchor work in 3D?

**Not as it stands.** The code is literally 2-D (`detect(double[] frame, int w,
int h, …)`), and when the method is extended to 3-D and measured, it falls a long
way short of what it achieves in 2-D.

## Measured

Fluo-N3DH-CE — *C. elegans* embryo nuclei, 35 × 512 × 708 voxels, 8-bit, silver
truth. Four volumes at t = 50/100/150/190, holding 26/93/192/360 cells (671
total). Voxel 0.09 µm in xy against 1.0 µm in z, i.e. **11× anisotropic**.

| | recall | precision | F1 | false positives | over-segmentations |
|---|---|---|---|---|---|
| 2-D, Fluo-N2DL-HeLa (2876 cells) | 91.6% | 94.1% | **92.8%** | 160 | **6** |
| 3-D, distance transform in voxels | 30.7% | 40.5% | 34.9% | 169 | 134 |
| 3-D, distance transform in µm | 65.7% | 50.6% | 57.2% | 361 | 69 |
| 3-D, + a sound threshold | 81.8% | 72.2% | **76.7%** | 2 | **209** |

## Three things, and they are different in kind

**1. Anisotropy has to be in the distance transform.** Computed in voxels, F1 is
34.9%; in physical units, 57.2%. A nucleus spanning 30 voxels in xy spans 3 in z,
so a voxel-unit transform thinks every nucleus is a thin plate and the watershed
splits it along z. This is a straightforward fix — `sampling` in physical units —
and the 2-D code has no equivalent because a plane is isotropic.

**2. The σ estimator degenerates on quantised data.** Fluo-N3DH-CE is 8-bit with
a background sitting on one grey level, so more than half the samples are
identical and the MAD is exactly **0**. `median + 3σ` collapses to `> median`.
Fixing the threshold moved F1 from 57.2% to 76.7%.

This was a real defect in shipped code, not only in the benchmark: `ImageProfile`
divides by that σ and returns `undetermined` when it is zero — failing *safe*,
but switching every intensity test off on a 3-D fluorescence movie with excellent
signal. Fixed: σ floors at half the background's quantisation step, which is the
most that can honestly be claimed about noise smaller than one grey level. The
step is measured on the background alone, because the gap up to the bright tail
is the largest difference in the data and would otherwise become the ruler.

**3. Crowding is the wall, and it is not a bug.** At t = 190, **356 of 360**
nuclei touch another nucleus directly, and cells fill 10.5% of the volume against
3.5% of the 2-D frame. So splitting is the entire problem — and note where the
error went: 2 false positives but **209 over-segmentations** in 671 cells, where
the 2-D run had 6 in 2876. The failure mode inverted.

## What this means for the rebuild

The anchor is not "the new detector". It is an external reference for the images
where measuring pixels answers the question, and the measurement now says where
that is:

* **bright objects, well separated** — authoritative (F1 93%);
* **bright objects, densely packed in 3-D** — useful as a *seed* and as a
  divergence check, but not as ground truth, because it over-segments;
* **phase contrast / brightfield** — no answer at all, by construction.

That is a more useful result than a uniform yes. It says the learned detector
earns its place exactly where crowding or contrast defeats direct measurement —
and that in those cases it must still be anchored on something outside itself,
which then has to be human annotation rather than the image.
