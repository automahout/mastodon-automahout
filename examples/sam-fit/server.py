"""Automahout shape-fit server: a micro-SAM mask at each detection's centre, returned as its centroid and moments.

    uv run python server.py [--port 8766] [--model vit_b_lm] [--device mps|cuda|cpu]

Speaks automahout-remote/1 (docs/remote-tools.md). Detectors (GET /tools, chosen by Automahout's detector trial like
any remote detector): microsam.ais (one plane) and microsam.ais3d (a z-stack, each plane segmented and joined
across planes), micro-SAM's automatic instance segmentation. And one shape fitter:

  GET  /fit                 {"protocol", "fitters": [{"id": "sam.fit", "description", "model"}]}
  POST /fit/sam.fit/run     {"frame": {"shape": [Y, X], "dtype": "float32", "data": <base64 little-endian>},
                             "points": [[x, y], ...], "negatives": 4}
                         -> {"fits": [{"centroid": [x, y], "moments": [[sxx, sxy], [sxy, syy]], "area": n} | null, ...],
                             "message"}

Each point is prompted on its own, with the `negatives` nearest other points as background prompts (measured on
PhC-C2DL-PSC: 0.65 -> 0.71 IoU against silver truth; no change on fluorescence). Coordinates are pixels of the
plane sent; moments are the mask's pixel second moments (variance; the client scales them to the ellipse).
A point whose mask is empty, or does not contain the point, gets null.

The model weights are downloaded by micro_sam on first use (vit_b_lm: about 410 MB, cached).
"""
import argparse
import base64
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from collections import OrderedDict

import numpy as np
import xxhash
from scipy.spatial import cKDTree
from skimage.measure import regionprops

PROTOCOL = 'automahout-remote/1'
STATE = {}
LOCK = threading.Lock()  # one model, one device: requests take turns

# The image encoder is the cost (about 1.3 s a plane on an M2); prompts are cheap. A window fits the same frames
# several times (each tuning round, the commit, the refinement), so embeddings are kept by the plane's pixels.
CACHE = OrderedDict()
CACHE_SIZE = 256  # vit_b: about 4 MB each
STATS = {'hits': 0, 'misses': 0}


def _embedding(image):
    from micro_sam.util import precompute_image_embeddings
    key = xxhash.xxh3_128_hexdigest(np.ascontiguousarray(image).tobytes()) + str(image.shape)
    if key in CACHE:
        CACHE.move_to_end(key)
        STATS['hits'] += 1
        return CACHE[key]
    STATS['misses'] += 1
    emb = precompute_image_embeddings(STATE['predictor'], _norm8(image), verbose=False)
    CACHE[key] = emb
    while len(CACHE) > CACHE_SIZE:
        CACHE.popitem(last=False)
    return emb


def _norm8(image):
    lo, hi = np.quantile(image, [0.005, 0.999])
    return (np.clip((image - lo) / max(hi - lo, 1e-6), 0, 1) * 255).astype(np.uint8)


def fit(image, points, negatives):
    from micro_sam.prompt_based_segmentation import segment_from_points
    pred = STATE['predictor']
    emb = _embedding(image)
    pts = np.asarray(points, dtype=float).reshape(-1, 2)
    tree = cKDTree(pts) if len(pts) > 1 else None
    out = []
    for i, (x, y) in enumerate(pts):
        prompt, labels = [[y, x]], [1]
        if tree is not None and negatives > 0:
            _, nb = tree.query(pts[i], k=min(negatives + 1, len(pts)))
            for j in np.atleast_1d(nb):
                if j != i and len(prompt) <= negatives:
                    prompt.append([pts[j][1], pts[j][0]])
                    labels.append(0)
        m = np.asarray(segment_from_points(pred, np.array(prompt), np.array(labels), image_embeddings=emb)).squeeze() > 0
        yi, xi = int(round(y)), int(round(x))
        if not m.any() or not (0 <= yi < m.shape[0] and 0 <= xi < m.shape[1]) or not m[yi, xi]:
            out.append(None)
            continue
        ys, xs = np.nonzero(m)
        cx, cy = xs.mean(), ys.mean()
        dx, dy = xs - cx, ys - cy
        out.append({'centroid': [float(cx), float(cy)],
                    'moments': [[float((dx * dx).mean()), float((dx * dy).mean())],
                                [float((dx * dy).mean()), float((dy * dy).mean())]],
                    'area': int(m.sum())})
    return out


def _detections(labels):
    """One detection per labelled region: centroid and the mask's second moments in pixels, (x, y[, z]).
    As examples/remote-tools/server.py: + 1/12 per axis on a stack, so a one-plane object is not flat."""
    out = []
    for r in regionprops(labels):
        idx = np.nonzero(labels[r.slice] == r.label)
        if len(idx[0]) < 4:
            continue
        off = [sl.start for sl in r.slice]
        if labels.ndim == 3:
            zz, rr, cc = idx
            cov = np.cov(np.vstack([cc, rr, zz])) + np.eye(3) / 12.0
            out.append({'centroid': [float(cc.mean() + off[2]), float(rr.mean() + off[1]), float(zz.mean() + off[0])],
                        'moments': np.atleast_2d(cov).tolist(), 'score': 1.0})
        else:
            rr, cc = idx
            out.append({'centroid': [float(cc.mean() + off[1]), float(rr.mean() + off[0])],
                        'moments': np.cov(np.vstack([cc, rr])).tolist(), 'score': 1.0})
    return out


class MicroSamAIS:
    """micro-SAM automatic instance segmentation as a detector; no parameters, no cell size assumed."""

    def __init__(self, volumetric):
        self.volumetric = volumetric
        self.id = 'microsam.ais' + ('3d' if volumetric else '')
        self.manifest = {
            'id': self.id,
            'description': "micro-SAM (%s) automatic instance segmentation of light-microscopy cells and nuclei%s"
                           % (STATE['model'], '; each plane, joined across planes into 3D objects.' if volumetric
                              else ', one plane.'),
            'role': 'DETECT',
            'parameters': [],
            # Trained on fluorescence and label-free light microscopy alike: no modality required.
            'applicability': {'requires': {'volumetric': volumetric}, 'score': 0.8,
                              'reason': 'generalist light-microscopy model' + (', a z-stack' if volumetric else ', one plane')},
        }

    def run(self, image, params, frame):
        from micro_sam.automatic_segmentation import automatic_instance_segmentation
        if 'segmenter' not in STATE:
            from micro_sam.automatic_segmentation import get_predictor_and_segmenter
            STATE['ais_predictor'], STATE['segmenter'] = get_predictor_and_segmenter(
                model_type=STATE['model'], device=STATE['device'], is_tiled=False)
        labels = automatic_instance_segmentation(predictor=STATE['ais_predictor'], segmenter=STATE['segmenter'],
                                                 input_path=_norm8(image), ndim=image.ndim, verbose=False)
        labels = np.asarray(labels).astype(np.int32)
        return _detections(labels), f'{self.id}: {int(labels.max())} objects'


TOOLS = {}


class Handler(BaseHTTPRequestHandler):
    def _send(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        path = self.path.rstrip('/')
        if path == '/tools':
            self._send(200, {'protocol': PROTOCOL, 'tools': [t.manifest for t in TOOLS.values()]})
        elif path == '/fit':
            self._send(200, {'protocol': PROTOCOL, 'fitters': [{
                'id': 'sam.fit', 'model': STATE['model'],
                'description': 'micro-SAM mask at each detection centre (neighbours as background prompts)'}]})
        else:
            self._send(404, {'error': 'unknown path ' + self.path})

    def do_POST(self):
        parts = self.path.strip('/').split('/')
        if len(parts) == 3 and parts[0] == 'tools' and parts[2] == 'run':
            return self._detect(parts[1])
        if self.path.strip('/') != 'fit/sam.fit/run':
            return self._send(404, {'error': 'unknown path ' + self.path})
        try:
            req = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            f = req['frame']
            if f.get('dtype', 'float32') != 'float32':
                raise ValueError('only float32 frames')
            image = np.frombuffer(base64.b64decode(f['data']), dtype='<f4').reshape(f['shape'])
            if image.ndim == 3 and image.shape[0] == 1:
                image = image[0]
            if image.ndim != 2:
                raise ValueError('sam.fit takes one plane; got shape %s' % (image.shape,))
            t0 = time.time()
            with LOCK:
                fits = fit(image, req.get('points') or [], int(req.get('negatives', 4)))
            n = sum(f is not None for f in fits)
            print(f'fit: {n} of {len(fits)} in {time.time() - t0:.1f}s; embeddings reused {STATS["hits"]}, computed {STATS["misses"]}', flush=True)
            self._send(200, {'fits': fits, 'message': f'{n} of {len(fits)} fitted in {time.time() - t0:.1f}s '
                                                      f'(embeddings reused {STATS["hits"]}, computed {STATS["misses"]})'})
        except Exception as e:  # report, do not crash the server
            self._send(500, {'error': f'{type(e).__name__}: {e}'})

    def _detect(self, tool_id):
        if tool_id not in TOOLS:
            return self._send(404, {'error': 'unknown tool ' + tool_id})
        try:
            req = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            f = req['frame']
            if f.get('dtype', 'float32') != 'float32':
                raise ValueError('only float32 frames')
            image = np.frombuffer(base64.b64decode(f['data']), dtype='<f4').reshape(f['shape'])
            if image.ndim == 3 and image.shape[0] == 1:
                image = image[0]
            tool = TOOLS[tool_id]
            if image.ndim != (3 if tool.volumetric else 2):
                raise ValueError('%s takes %s; got shape %s' % (tool.id, 'a z-stack' if tool.volumetric else 'one plane',
                                                                image.shape))
            t0 = time.time()
            with LOCK:
                dets, msg = tool.run(image, req.get('params') or {}, f)
            print(f'{tool.id}: {len(dets)} detections in {time.time() - t0:.1f}s', flush=True)
            self._send(200, {'detections': dets, 'message': msg})
        except Exception as e:  # report, do not crash the server
            self._send(500, {'error': f'{type(e).__name__}: {e}'})

    def log_message(self, fmt, *args):
        print('%s - %s' % (self.address_string(), fmt % args))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--port', type=int, default=8766)
    ap.add_argument('--model', default='vit_b_lm')
    ap.add_argument('--device', default=None, help='mps, cuda or cpu (default: what torch finds)')
    a = ap.parse_args()
    from micro_sam.util import get_sam_model
    STATE['predictor'] = get_sam_model(model_type=a.model, device=a.device)
    STATE['model'] = a.model
    STATE['device'] = STATE['predictor'].device.type  # 'mps', not 'mps:0': micro_sam takes the type only
    for t in (MicroSamAIS(False), MicroSamAIS(True)):
        TOOLS[t.id] = t
    print(f'{PROTOCOL}: sam.fit and {", ".join(TOOLS)} ({a.model} on {STATE["predictor"].device}) on port {a.port}')
    ThreadingHTTPServer(('127.0.0.1', a.port), Handler).serve_forever()


if __name__ == '__main__':
    main()
