# Supplementary Fig. 4: Automahout in Fiji

`figS4.pptx`, `figS4.png`, made by `scripts/paper/figures/figS4.py` from screenshots of one GUI run. A shot that is
missing is drawn as a grey placeholder saying what to capture.

## The run

Fluo-N3DH-CE 01 in Mastodon (the BigDataViewer XML from `convert_ctc_to_bdv.py`), started from
Plugins > Automahout, through the first two windows (t = 0-19, about 15-20 min), then stopped with
Plugins > Automahout > stop. The set-up's defaults, except the training balance: cells at 1x, as in the reported runs.

## The shots

In `shots/`, taken by the authors on 2026-10-27 (Fluo-N3DH-CE 01, first windows); `d.png`, `e.png` and `f.png`
retaken on 2026-10-30 after the set-up's training-balance labels were reworded, with cells at 1x. The figure uses
seven of them:

| Panel | File | Shows |
|---|---|---|
| a | `a.png` | the pre-flight starting: Automahout's log beside BigDataViewer at t = 0 |
| b | `g.png` | the first window's review: cell radius and reference cells (cyan) |
| c | `h.png` | the end of the first window: fine-tune done, tracks at t = 10 |
| d | `b.png` | set-up step 1, the data |
| e | `c.png` | set-up step 2, image type |
| f | `d.png` | set-up step 3, options |
| g | `f.png` | set-up step 4, instructions |

`e.png` (step 3 with the advanced settings open) is not used: step 3 alone is clearer. The panel order and the
files are `ROWS` in `scripts/paper/figures/figS4.py`.

## Command (from `scripts/paper`)

```
uv run python figures/figS4.py --shots ../../paper/figures/supp/figS4/shots --out ../../paper/figures/supp/figS4/figS4.pptx
```
