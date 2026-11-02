# Remote tools: protocol `automahout-remote/1`

A tool in any language takes part in Automahout as a small HTTP server. Automahout asks it what tools
it offers, decides from the data which of them apply, and sends it image frames to work on. The
server only ever sees pixels; Automahout converts to and from Mastodon's world coordinates.

Configure servers in Mastodon: *Plugins > Automahout > Run a tool...*, field "Remote tool servers"
(comma-separated base URLs, remembered). A server that does not answer is skipped and logged.

An example server offering Cellpose and StarDist detectors: `examples/remote-tools/server.py`.

## `GET /tools`

```json
{
  "protocol": "automahout-remote/1",
  "tools": [
    {
      "id": "cellpose.nuclei",
      "description": "Cellpose 'nuclei' model: segments nuclei, one detection per mask.",
      "role": "DETECT",
      "parameters": [
        { "name": "diameter", "type": "NUMBER", "description": "nucleus diameter in pixels; 0 = estimate",
          "default": 0, "min": 0, "max": 500 }
      ],
      "applicability": {
        "requires": { "fluorescenceLike": true, "volumetric": false },
        "score": 0.8,
        "reason": "nuclear stain on a dark background, one plane"
      }
    }
  ]
}
```

- `role`: one of the `ToolRole` names (`DETECT`, `LINK`, `SHAPE`, ...). Version 1 runs `DETECT` tools.
- `parameters[].type`: `INTEGER`, `NUMBER`, `BOOLEAN`, `STRING` or `CHOICE` (with `"choices": [...]`).
- `applicability.requires`: conditions on the run's `DataProfile`, each `true`, `false` or absent:
  `fluorescenceLike`, `volumetric`. A condition the profile cannot answer yet (modality not measured)
  is reported as unknown and lowers the score by half, rather than excluding the tool.

## `POST /tools/{id}/run`

One frame per request.

```json
{
  "params": { "diameter": 0 },
  "frame": {
    "t": 12,
    "shape": [700, 1100],
    "dtype": "float32",
    "voxel_size": [0.645, 0.645, 0.645],
    "cell_radius_um": 6.37,
    "data": "<base64 of the pixels, little-endian, C order (z, y, x) / (y, x)>"
  }
}
```

Optional frame fields, which a tool may use or ignore (an older server ignores them):

- `voxel_size`: world units (µm) per voxel along x, y, z.
- `cell_radius_um`: the cell radius Automahout measured in the image for this run (pre-flight cell
  size), when it measured one. A tool whose own size estimate fails can use it: the example server's
  `cellpose.nuclei3d` takes its diameter from it (`2 * cell_radius_um / voxel_size[0]` pixels), because
  Cellpose's size model put Fluo-N3DH-CE's nuclei at 3-13 px against ~40.

Response:

```json
{
  "detections": [
    { "centroid": [512.3, 233.9], "moments": [[31.0, 2.1], [2.1, 18.5]], "score": 0.97 }
  ],
  "message": "cellpose nuclei: 61 masks"
}
```

- `centroid`: pixel coordinates in `(x, y)` or `(x, y, z)` order -- x the column -- in pixels.
- The detection's shape, one of (pixels², same axis order as the centroid):
  - `moments`: the second moments (covariance) of the mask's pixels -- what a segmentation gives
    directly. Automahout multiplies by 4 (2D) or 5 (3D) to get the ellipsoid that fills the mask.
  - `covariance`: Mastodon's ellipsoid shape matrix, whose eigenvalues are the squared semi-axes (a
    sphere of radius r is r² I). Not the moments: for a filled ellipse it is 4x them, and sending the
    moments here gives spots of half the size.
  - `radius` (pixels): a sphere.

  Automahout then scales the shape to world units.
- `score`: optional, 0..1.
- An error is an HTTP status other than 200 with `{"error": "..."}`; Automahout logs it and adds nothing
  for that frame.

## Shape fitters: `GET /fit` and `POST /fit/{id}/run`

A server may also offer shape fitters, which reshape detections Automahout already has instead of adding new
ones. They are listed apart from `/tools`, so a fitter is never chosen as a detector. The detection-stage shape
fit uses the first configured server listing `sam.fit` (example: `examples/sam-fit`).

`GET /fit` returns `{"protocol": "automahout-remote/1", "fitters": [{"id": "sam.fit", "description": ..., "model": ...}]}`.

`POST /fit/sam.fit/run` takes one plane and the detections on it, in that plane's pixels:

```json
{"frame": {"t": 0, "shape": [Y, X], "dtype": "float32", "data": "<base64, little-endian>"},
 "points": [[x, y], ...], "negatives": 4}
```

and returns, in the order of `points`, `{"fits": [{"centroid": [x, y], "moments": [[sxx, sxy], [sxy, syy]], "area": n} | null, ...], "message": ...}`.
`moments` are the mask's pixel variances; the client scales them by 4 to an ellipse's squared semi-axes. `null`
means no mask held the point, and that detection keeps the contour fit.
