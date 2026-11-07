# Settings

Open the settings dialog with **Plugins › Automahout › Automahout Settings…**. It has two tabs. Changes are
saved on **OK** in the user's Java preferences, so they apply to every project opened by the same user until
changed again.

## VLM / LLM tab

The language model Automahout talks to, and the pre-flight analysis that shows it the movie.

| Field | Default | Description |
|---|---|---|
| **Backend** | `ollama` | `ollama` (a local [Ollama](https://ollama.com) server), `anthropic` (the Anthropic API) or `bedrock` (Claude through Amazon Bedrock). |
| **Model name / tag** | `llava` | The model id: an Ollama tag, an Anthropic model id, or a Bedrock model or inference-profile id. |
| **Ollama base URL** | `http://localhost:11434` | Shown for `ollama` only. |
| **Anthropic API key** | empty | Shown for `anthropic` only. Leave it empty to use the `ANTHROPIC_API_KEY` environment variable. |
| **AWS region** | `ap-northeast-1` | Shown for `bedrock` only. Credentials come from the `AWS_BEARER_TOKEN_BEDROCK` environment variable or the AWS SDK's default chain (environment, `~/.aws/credentials`, IAM role). |
| **Max snapshots** | 8 | Screenshots the pre-flight analysis sends to the model (1–64). |
| **Z slices / timepoint** | 3 | Z slices captured per sampled timepoint, evenly spaced through the stack (1–16; 1 = the current slice). |
| **Output file** | `docs/video_description.md` | The Markdown file the pre-flight analysis (`video.analyze`) writes, relative to the working directory or absolute. |

## Tracking tab

### Range

| Field | Default | Description |
|---|---|---|
| **Start timepoint (-1 = first)** | -1 | The first timepoint tracked and corrected. |
| **End timepoint (-1 = last)** | -1 | The last timepoint tracked and corrected (inclusive). |

### Correction

These are parameters of the per-frame correction passes (the `FRAME` plan in
[Architecture](architecture.md), section 1.6). A value of 0, or an unticked box, switches the pass off.

| Field | Default | Pass | Description |
|---|---|---|---|
| **Merge overlap ratio** | 0 (off) | `frame.merge_duplicates` | Merges two spots whose centres are closer than `ratio × max(r1, r2)`. |
| **Fill track gaps** | off | `elephant.fill_gaps`, `elephant.recover_predecessors` | Re-detects with ELEPHANT around a track that ends (at t+1) or starts without a predecessor (at t−1), and links what it finds. Only when ELEPHANT detects. |
| **Gap crop factor** | 3.0 | the two above | The crop ELEPHANT re-detects in reaches `factor × spot radius` on each side. Larger gives it more context. |
| **Min tracklet length** | 5 | `frame.short_tracklets` | Deletes a dangling tracklet shorter than this many timepoints. Never at the last timepoint a run has reached, where tracks have no future yet. |
| **Long-link factor** | 3.0 | `frame.long_links` | Cuts a link longer than `factor × max(r_source, r_target)`, or longer than the per-frame displacement cap when one is known; the spot left without a predecessor is re-linked by ELEPHANT's linker. |
| **Min intensity ratio** | 0 (off) | `frame.weak_spots` | Deletes a spot whose intensity at its centre is below `ratio × the frame's median`. Applies to spots without an ELEPHANT quality score; see below. |
| **Min size ratio** | 0 (off) | `frame.small_spots` | Deletes a spot whose radius is below `ratio × the frame's median radius`. |
| **Refit anisotropic spots** | off | `elephant.refit_anisotropic` | Re-detects an elongated spot with ELEPHANT in a crop around it and replaces it. One server call per spot, so slow. Only when ELEPHANT detects. |
| **Anisotropy threshold** | 10.0 | the refit above | A spot is elongated when `max(diag(Σ)) / min(diag(Σ))` of its covariance exceeds this. |
| **Division link factor** | 0 (off) | `frame.division_daughters` | Links a track start to a dividing mother at t−1 within `factor × max(r_start, r_mother)`. |

The weak-spot and small-spot passes skip the first and last timepoints, because they judge a spot against its
neighbours in time.

**ELEPHANT's quality score.** A detection that carries ELEPHANT's quality score is judged by it instead of by
intensity: in `auto.correct` it is deleted when its quality is below 0.5, whatever **Min intensity ratio** says.
That threshold is not in the dialog.

### Where these values take effect

A run corrects in two places, and they read the dialog differently:

- **Inside each window of the adaptive tracking** (`autocorrect.window`), only **Long-link factor** and
  **Merge overlap ratio** come from the dialog, unless the agent passes them to `adaptive_track`. The rest are fixed off there, because the window's last frame has
  no links forward yet: min tracklet length, min intensity and size ratios, division link factor and refitting.
  Gap filling runs only in the window's refinement rounds, with the dialog's **Gap crop factor**.
- **`auto.correct`**, which the agent calls over the whole movie after the adaptive tracking, reads every
  field, with two exceptions: **Fill track gaps** is on and **Min tracklet length** is 0 unless the agent passes
  them. The agent can also override any field for one call (below).

## `auto.correct` parameters

The agent passes these to `auto.correct`; a parameter it leaves out takes the value described.

| Parameter | Type | Left out |
|---|---|---|
| `t` | int | The viewer's current timepoint; `-1` corrects every timepoint. |
| `long_link_factor` | double | **Long-link factor** |
| `merge_overlap_ratio` | double | **Merge overlap ratio** |
| `min_intensity_ratio` | double | **Min intensity ratio** |
| `min_size_ratio` | double | **Min size ratio** |
| `fill_gaps` | boolean | on |
| `gap_crop_factor` | double | **Gap crop factor** |
| `min_tracklet_length` | int | 0 (off) |
| `division_link_factor` | double | **Division link factor** |
| `refit` | boolean | **Refit anisotropic spots** |
| `anisotropy_threshold` | double | **Anisotropy threshold** |
| `min_branch_length` | int | Deletes a branch shorter than this after a division. 0, or the 5th percentile of track-segment length when a learned lineage-constraint profile is active. |
| `min_division_interval` | int | The shortest plausible time between two divisions in one lineage (with `t = -1`). From a learned lineage-constraint profile when one is active, else estimated from how fast the cell count grows. |
