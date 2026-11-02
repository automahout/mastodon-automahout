# What the bottleneck is

## The measurement

A classical detector — threshold at `background + 3σ`, split touching nuclei by
watershed on the distance transform, drop regions under 50 px — scored against
the CTC silver truth of Fluo-N2DL-HeLa, 31 frames, 2876 cells:

| | recall | precision | F1 | false positives | over-segmentations |
|---|---|---|---|---|---|
| **classical, no model** | 91.6% | 94.1% | **92.8%** | 160 | **6** |

Six over-segmentations in 2876 cells. No training, no iteration, milliseconds
per frame, fifteen lines of arithmetic.

For comparison, the pipeline on the same ten timepoints, after about five
minutes of training per window: 53.3 spots/tp against a truth of 46.8 — and it
could not hold that, because the refinement round then pushed t=0 to 146 and the
window was discarded.

## What this says

The detection problem on this class of image is not hard, and the pipeline is
not failing at it because of any of the thresholds I have been adjusting.

The pipeline trains ELEPHANT **on its own detections**, then damps the resulting
feedback loop with a long chain of independent heuristic filters. Everything
that went wrong this session was in that chain:

* the valley test merged 9.0% of the pairs it existed to merge — it was inert,
  so tuning it could never have worked;
* four annotation guards were applied at one training site of the four that
  needed them, and the unguarded one threw away a good window;
* the fp synthesis could not manufacture negatives (77% of candidates rejected)
  while measured background sat unused in the graph;
* the annotation-extent correction, meant to fix undersized labels, caused the
  explosion it was added near;
* the merge size cap read the covariance diagonal and let tilted merges grow 27%
  past it.

These are not five unrelated bugs. They are what a chain of independent
thresholds does when nothing outside the loop says what a cell is. Each filter
can only be calibrated against the output of a model that is itself being
calibrated against those filters.

## What the rebuild is

**Anchor the detector on the image, not on itself.**

`ClassicalDetector` is that anchor. It depends on pixels alone, so it cannot
drift with the model, and on fluorescence it is already better than what the
loop produces. `ImageProfile` already decides whether an image is of that kind.

Staged:

1. **Anchor exists** — `ClassicalDetector`, verified in Java against real pixels
   (`ClassicalDetectorFixtureTest`: recall 92.0%, precision 95.8%, F1 93.9%,
   0 false positives, 2 over-segmentations on the densest crops).
2. **Cold start from the anchor** — training annotations come from it instead of
   20 intensity maxima, so ELEPHANT starts from a 93%-F1 label set.
3. **Judge each round against the anchor**, not against the previous round, so
   divergence is detected by disagreement with the image rather than by a growth
   ratio.
4. **Retire the damping chain** — the filters that exist only to control
   self-training have nothing left to do on this path, and each one retired is
   one fewer threshold to be wrong.
5. **Non-fluorescence and 3D** — the anchor does not work where brightness does
   not mean signal, and that is where a learned detector earns its place. The
   rule that must survive there is the same one: it is bootstrapped from
   something outside itself (human annotation), never from its own output.

## Honest limits

This is a 2-D, in-plane detector. `ImageProfile.flatContrastDeletionApplies` /
`dimSignalFilterApplies` already gate the intensity-based tests to the images
where they mean something, and this is subject to the same gate. It is a good
answer for fluorescence and no answer at all for phase contrast, which is
precisely why the rest of the machinery should not be deleted — only demoted
from "the way detection works" to "the way detection works where measuring the
image cannot".
