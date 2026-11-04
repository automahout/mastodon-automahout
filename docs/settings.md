# Automahout Settings Reference

Open the settings dialog via **Plugins › Automahout › Automahout Settings…**. It has two tabs; every field
is read by the tracking pipeline, and changes are saved on **OK**.

---

## VLM / LLM tab

| Parameter | Description |
|---|---|
| **Backend** | `ollama` (a local Ollama server), `anthropic` (the Anthropic API) or `bedrock` (Claude through Amazon Bedrock). |
| **Model name / tag** | The model id, e.g. a Bedrock inference-profile id. |
| **Ollama base URL** | Shown for `ollama` only (default `http://localhost:11434`). |
| **Anthropic API key** | Shown for `anthropic` only; leave empty to use the `ANTHROPIC_API_KEY` environment variable. |
| **AWS region** | Shown for `bedrock` only; credentials come from `AWS_BEARER_TOKEN_BEDROCK` or the AWS SDK's default chain. |
| **Max snapshots** | Screenshots the pre-flight analysis sends to the model. |
| **Z slices / timepoint** | Z slices captured per sampled timepoint (1 = the current slice). |
| **Output file** | The Markdown file the pre-flight analysis (`video.analyze`) writes. |

---

## Tracking tab

### Range

| Parameter | Default | Description |
|---|---|---|
| **Start timepoint (-1 = first)** | -1 | First timepoint the tracking and correction process. |
| **End timepoint (-1 = last)** | -1 | Last timepoint processed (inclusive). |

### Correction

Parameters that control the autonomous correction pass (Phase 3), which runs after detection and linking.  
All correction strategies are applied per timepoint in the order listed below.

#### 1. Long-link pruning

| Parameter | Default | Description |
|---|---|---|
| **Long-link factor** | 3.0 | A link is considered spurious when its Euclidean length exceeds `factor × max(r_source, r_target)`, where `r` is the bounding-sphere radius of each endpoint spot. The link is deleted and the orphaned spot is immediately re-linked by ELEPHANT nearest-neighbour linking. Increase to tolerate faster-moving cells; decrease to prune more aggressively. |

#### 2. Weak-spot deletion (intensity-based)

| Parameter | Default | Description |
|---|---|---|
| **Min intensity ratio** | 0.0 (disabled) | For each non-terminal timepoint, the voxel intensity at the centre of every spot is sampled from the first image source. Spots whose intensity is below `ratio × median_intensity` are deleted. Setting this to `0.0` disables the check entirely. A value of `0.1` removes spots whose signal is less than 10 % of the median — useful for cleaning up low-confidence ELEPHANT detections without re-training. |

> **Note:** Intensity sampling uses the source transform at mipmap level 0 (full resolution). Spots outside the image bounds return intensity `0` and will be deleted if the ratio is non-zero. Check that all spots lie within the image volume before enabling this setting.

#### 3. Isolated-spot deletion

Spots at non-terminal timepoints that have no incoming **and** no outgoing edges after pruning and re-linking are automatically deleted. This step has no configurable parameter.

#### 4. Anisotropic-spot refit (optional)

| Parameter | Default | Description |
|---|---|---|
| **Refit anisotropic spots** | off | When enabled, spots whose covariance matrix is highly anisotropic are refitted by calling ELEPHANT `/seg/predict` with a crop box centred on the spot. Requires a running ELEPHANT server. Off by default because it significantly increases correction time. |
| **Anisotropy threshold** | 10.0 | A spot is considered anisotropic — and thus a refit candidate — when `max(diag(Σ)) / min(diag(Σ)) > threshold`, where `Σ` is the covariance matrix. Increase to refit only the most extreme shapes; decrease to refit more broadly. |

---

## Tool parameters (advanced)

The `auto.correct` tool exposed to the LLM accepts the following parameters that override the dialog values for a single call:

| Parameter | Type | Corresponding setting |
|---|---|---|
| `t` | int | Timepoint to correct (`-1` = all). Default: current viewer timepoint. |
| `long_link_factor` | double | Overrides **Long-link factor** |
| `min_intensity_ratio` | double | Overrides **Min intensity ratio** |
| `refit` | boolean | Overrides **Refit anisotropic spots** |
| `anisotropy_threshold` | double | Overrides **Anisotropy threshold** |
