# VLM crosshair benchmark

A fixed set of crops with **measured** ground truth, for the one question the pipeline asks a VLM
over and over: *does the red crosshair sit on a cell, or on empty background?*

Every synthetic fp training negative depends on that answer. Until this set existed the answers
could not be checked, so three separate explanations for the pipeline's behaviour stayed live at
once.

## Why it was built

Scoring ~9,800 crops from `~/.automahout-vlm-debug/` against measured ground truth:

| | called "background" |
|---|---|
| 2444 crops that are genuinely **empty** at the crosshair | **17.1 %** |
| 17 crops that genuinely hold a **cell** at the crosshair | **35.3 %** |

It was likelier to call a real cell empty than to call empty space empty. Two batches of 74 crops,
seconds apart in the same window on statistically identical candidates, returned **0** and **57**
background verdicts. The verdicts were a property of the batch, not of the images.

That is why the fp-negative supply came in at 0.9 % of tp annotations against a 10 % target.

## What the ground truth is

Not a judgement call. `build_set.py` locates the red crosshair **by its own pixels** — it is not
always at the centre; 18 % of crops are clamped at an image border and put it elsewhere — and
measures the image intensity in a small disc around it:

* `>= 200` -> `cell`
* `<= 60` -> `background`
* anything between -> left out rather than guessed

Crops rendered entirely black or entirely white are unanswerable however they are labelled and are
excluded (8.8 % of what was scanned).

The crops themselves (`images/`) are from our own unpublished data and are not in the repository; `labels.tsv`
and the scripts are, so the set can be rebuilt on other data with `build_set.py`.

`labels.tsv` records, per crop: the truth, the measured intensity at the crosshair, the frame
median, whether the crosshair is off-centre, and the verdict the model gave when the crop was
captured. That last column is history, not truth — never score against it.

## Running it

The measurement makes real, paid API calls, so it is a `main`, not a test:

```bash
mvn -o test-compile
mvn -o exec:java -Dexec.classpathScope=test \
    -Dexec.mainClass=org.mastodon.mamut.automahout.vision.VlmFpBenchmark \
    -Dexec.args="bedrock jp.anthropic.claude-haiku-4-5-20251001-v1:0 ap-northeast-1 1,5,10,30,60"
```

It sweeps batch size, because batch size is the open question: `VLM_MAX_IMAGES_PER_CALL` is 99 and
the API cap for a 200K-context model is 100 per request, so production runs at the edge.

It reports two numbers per batch size:

* **empty -> empty** decides the negative supply: the fraction of genuinely empty positions the model
  will hand over as training negatives. Production measured **17 %**.
* **cell -> cell** is the safety side: a cell called empty is taught to the detector as background.

`VlmBenchmarkSetTest` runs in CI, costs nothing, and only checks the set is internally consistent
and not lopsided — a set of only empty crops is passed by answering "empty" to everything.

## Changing the set

Re-run `build_set.py` (needs `pillow`); commit the result. Grow it before tuning against it — 30 per
class separates 17 % from chance, but not 45 % from 55 %.

```bash
python3 build_set.py --dumps 400 --per-class 30
```

## What it does not measure

The rendering. Crop contrast is set by `vlmDisplayRange`, which anchors on background statistics
sampled at mipmap level 1 while `captureSpotCropPng` reads level 0 — so the display window is about
half as wide as the pixels it is drawn from need. These crops carry that bug baked in. Fixing it
changes the images, which means re-running `build_set.py` and re-measuring from there.
