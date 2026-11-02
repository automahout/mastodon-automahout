# Automahout Settings Reference

Open the settings dialog via **Plugins › Automahout › Automahout Settings…**

---

## Detection

Parameters that control how ELEPHANT detects spots in each timepoint during Phase 1.

| Parameter | Default | Description |
|---|---|---|
| **Max spots per timepoint** | 10 | Upper bound on the number of spots added to the graph per timepoint. Increase if the field of view contains many cells; reduce to limit over-detection. |
| **Mipmap level (-1 = auto)** | -1 | Resolution level used for image scanning. `-1` selects the coarsest available level (fastest). `0` uses full resolution. Intermediate values select finer levels. Coarser levels are sufficient for detection and greatly reduce computation time. |
| **Stride XY (voxels)** | 4 | Sampling stride in X and Y. Every N-th voxel is evaluated in each lateral plane. Smaller values increase recall at the cost of speed. |
| **Stride Z (voxels)** | 1 | Sampling stride in Z. `1` evaluates every z-plane. Increase for data with high z-sampling density. |
| **Suppression radius (px)** | 50 | Non-maximum suppression radius in screen pixels. Two candidate spots closer than this distance (in screen space) are merged into one. Increase if duplicate spots appear; decrease if adjacent cells are merged. |

---

## Spot

| Parameter | Default | Description |
|---|---|---|
| **Spot radius (world units)** | 5.0 | Radius assigned to newly created spherical spots. The unit matches the physical calibration of the dataset (e.g. µm). This is also used as the reference scale for the long-link factor in correction. |

---

## Navigation

| Parameter | Default | Description |
|---|---|---|
| **Navigate animation (ms)** | 500 | Duration of the pan-to animation when the BDV viewer moves to a new spot position. Set to `0` for instant navigation. |

---

## Correction

Parameters that control the autonomous correction pass (Phase 3), which runs after detection and linking.  
All correction strategies are applied per timepoint in the order listed below.

### 1. Long-link pruning

| Parameter | Default | Description |
|---|---|---|
| **Long-link factor** | 3.0 | A link is considered spurious when its Euclidean length exceeds `factor × max(r_source, r_target)`, where `r` is the bounding-sphere radius of each endpoint spot. The link is deleted and the orphaned spot is immediately re-linked by ELEPHANT nearest-neighbour linking. Increase to tolerate faster-moving cells; decrease to prune more aggressively. |

### 2. Weak-spot deletion (intensity-based)

| Parameter | Default | Description |
|---|---|---|
| **Min intensity ratio** | 0.0 (disabled) | For each non-terminal timepoint, the voxel intensity at the centre of every spot is sampled from the first image source. Spots whose intensity is below `ratio × median_intensity` are deleted. Setting this to `0.0` disables the check entirely. A value of `0.1` removes spots whose signal is less than 10 % of the median — useful for cleaning up low-confidence ELEPHANT detections without re-training. |

> **Note:** Intensity sampling uses the source transform at mipmap level 0 (full resolution). Spots outside the image bounds return intensity `0` and will be deleted if the ratio is non-zero. Check that all spots lie within the image volume before enabling this setting.

### 3. Isolated-spot deletion

Spots at non-terminal timepoints that have no incoming **and** no outgoing edges after pruning and re-linking are automatically deleted. This step has no configurable parameter.

### 4. Anisotropic-spot refit (optional)

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
