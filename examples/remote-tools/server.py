"""Example Automahout remote-tool server: Cellpose and StarDist detectors over automahout-remote/1.

    python server.py [--port 8765]

Needs numpy and scikit-image; cellpose and/or stardist for the tools that use them (a tool whose
package is missing is not offered). Protocol: docs/remote-tools.md. Standard library HTTP only.
"""
import argparse
import base64
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import numpy as np
from skimage.measure import regionprops

PROTOCOL = 'automahout-remote/1'


def _have(module):
    try:
        __import__(module)
        return True
    except Exception:
        return False


def _detections(labels):
    """One detection per labelled region: centroid (x, y) and the mask's second moments in pixels.

    Sent as "moments", not "covariance": Automahout's covariance is Mastodon's ellipsoid shape matrix
    (eigenvalues = squared semi-axes), which for a filled ellipse is 4x its second moments; Automahout
    does that conversion (docs/remote-tools.md)."""
    out = []
    for r in regionprops(labels):
        if labels.ndim == 3:  # a stack: (z, y, x) -> centroid and moments in (x, y, z)
            idx = np.nonzero(labels[r.slice] == r.label)
            if len(idx[0]) < 4:
                continue
            zz, rr, cc = idx
            off = [s.start for s in r.slice]
            # + 1/12 per axis: the variance of a position spread over one voxel. Without it an object on a
            # single plane has zero spread in z -- a flat, singular ellipsoid.
            cov = np.cov(np.vstack([cc, rr, zz])) + np.eye(3) / 12.0
            out.append({'centroid': [float(cc.mean() + off[2]), float(rr.mean() + off[1]), float(zz.mean() + off[0])],
                        'moments': np.atleast_2d(cov).tolist(), 'score': 1.0})
            continue
        cy, cx = r.centroid
        # inertia_tensor is in (row, col); covariance of the region's pixels, swapped to (x, y)
        rr, cc = np.nonzero(labels[r.slice] == r.label)
        if len(rr) < 3:
            continue
        cov = np.cov(np.vstack([cc, rr]))
        out.append({'centroid': [float(cx), float(cy)], 'moments': cov.tolist(), 'score': 1.0})
    return out



def load_stardist2d(name):
    """StarDist2D.from_pretrained(name), but working with Keras 3: its get_file(extract=True) unpacks into
    <folder>/<name>_extracted/, while csbdeep 0.8.0 reads <folder>/config.json, so a first download on a fresh
    machine fails ("config file doesn't exist"). Download (hash-checked) without unpacking, then unpack into
    the folder csbdeep reads."""
    import zipfile
    from pathlib import Path
    from csbdeep.models.pretrained import get_model_details, get_file
    from stardist.models import StarDist2D
    key, _, m = get_model_details(StarDist2D, name)
    zpath = Path(get_file(fname=key + '.zip', origin=m['url'], file_hash=m['hash'],
                          cache_subdir=str(Path('models') / 'StarDist2D' / key), extract=False))
    folder = zpath.parent
    if not (folder / 'config.json').exists():
        with zipfile.ZipFile(zpath) as z:
            z.extractall(folder)
    model = StarDist2D(None, name=key, basedir=str(folder.parent))
    model.basedir = None  # read-only, as from_pretrained leaves it
    return model

def _gpu():
    """Cellpose on CUDA when there is one (it falls back to the CPU without a word otherwise)."""
    try:
        import torch
        return torch.cuda.is_available()
    except ImportError:
        return False


class Cellpose:
    """A Cellpose model as a tool. 2D: Cellpose's own diameter estimate unless one is given. 3D ('nuclei3d'):
    each plane in 2D, the planes' masks stitched into objects (IoU 0.25, Cellpose's stitch3D default), with
    the diameter from the cell radius Automahout measured in the image (frame.cell_radius_um and
    frame.voxel_size), since Cellpose's size model fails on anisotropic stacks -- the same procedure as the
    benchmark's baseline T (scripts/baselines/trackastra_baseline.py)."""
    STITCH_THRESHOLD = 0.25
    _models = {}
    _lock = threading.Lock()

    def __init__(self, model_type, volumetric=False):
        self.model_type = model_type
        self.volumetric = volumetric
        self.id = 'cellpose.' + model_type + ('3d' if volumetric else '')
        if model_type == 'nuclei':
            what = 'nuclei, one detection per mask. Suited to a nuclear stain on a dark background'
            requires = {'fluorescenceLike': True, 'volumetric': volumetric}
            reason = 'nuclear stain on a dark background'
        else:
            what = ('whole cells, one detection per mask: the generalist model (fluorescence, phase contrast, '
                    'brightfield)')
            requires = {'volumetric': volumetric}
            reason = 'cells in any 2D modality'
        self.manifest = {
            'id': self.id,
            'description': f"Cellpose '{model_type}' model: segments {what}"
                           + ('; each plane in 2D, stitched into 3D objects.' if volumetric else ', one plane.'),
            'role': 'DETECT',
            'parameters': [
                {'name': 'diameter', 'type': 'NUMBER',
                 'description': 'object diameter in pixels; 0 = ' + (
                     'from the cell size Automahout measured' if volumetric else "Cellpose's own estimate"),
                 'default': 0, 'min': 0, 'max': 500},
                {'name': 'flow_threshold', 'type': 'NUMBER', 'description': 'mask quality cut-off',
                 'default': 0.4, 'min': 0, 'max': 3},
            ],
            'applicability': {'requires': requires, 'score': 0.8,
                              'reason': reason + (', a z-stack' if volumetric else ', one plane')},
        }

    def _model(self):
        from cellpose import models
        if self.model_type not in Cellpose._models:
            Cellpose._models[self.model_type] = models.Cellpose(model_type=self.model_type, gpu=_gpu())
        return Cellpose._models[self.model_type]

    def run(self, image, params, frame):
        flow = float(params.get('flow_threshold', 0.4))
        d = float(params.get('diameter', 0)) or None
        with self._lock:
            model = self._model()
            if not self.volumetric:
                masks, _, _, diams = model.eval(image, diameter=d, channels=[0, 0], flow_threshold=flow)
                return _detections(masks), (f'cellpose {self.model_type}: {int(masks.max())} masks '
                                            f'(diameter {float(np.mean(diams)):.1f} px)')
            source = 'given'
            if d is None and frame.get('cell_radius_um') and frame.get('voxel_size'):
                d = 2 * float(frame['cell_radius_um']) / float(frame['voxel_size'][0])
                source = 'from the measured cell radius'
            if d is None:
                _, _, _, est = model.eval(image[image.shape[0] // 2], diameter=None, channels=[0, 0])
                d = float(est)
                source = "Cellpose's estimate on the middle plane (no measured cell radius was sent)"
            masks, _, _, _ = model.eval(image, diameter=d, channels=[0, 0], do_3D=False, flow_threshold=flow,
                                        stitch_threshold=Cellpose.STITCH_THRESHOLD)
        return _detections(masks), (f'cellpose {self.model_type} per plane, stitched: {int(masks.max())} objects '
                                    f'(diameter {d:.1f} px, {source})')


class StarDist:
    def __init__(self, model, fluo):
        self.model_name = model
        self.id = 'stardist.' + ('fluo' if fluo else 'he')
        self.manifest = {
            'id': self.id,
            'description': f"StarDist '{model}': star-convex nuclei. "
                           + ('For a fluorescent nuclear stain.' if fluo else 'For stained (H&E-like) brightfield images.'),
            'role': 'DETECT',
            'parameters': [
                {'name': 'prob_thresh', 'type': 'NUMBER', 'description': 'object probability cut-off; 0 = model default',
                 'default': 0, 'min': 0, 'max': 1},
            ],
            'applicability': {'requires': {'fluorescenceLike': fluo, 'volumetric': False}, 'score': 0.75 if fluo else 0.7,
                              'reason': 'fluorescent nuclei, one plane' if fluo else 'stained nuclei in brightfield, one plane'},
        }
        self._model = None
        self._lock = threading.Lock()

    def run(self, image, params, frame):
        from csbdeep.utils import normalize
        with self._lock:
            if self._model is None:
                self._model = load_stardist2d(self.model_name)
            img = normalize(image, 1, 99.8)
            if self.id == 'stardist.he':
                img = np.stack([img] * 3, axis=-1)  # the H&E model expects RGB
            p = float(params.get('prob_thresh', 0)) or None
            labels, _ = self._model.predict_instances(img, prob_thresh=p)
        return _detections(labels), f'stardist {self.model_name}: {int(labels.max())} objects'


class CellposeSAM:
    """Cellpose-SAM (Cellpose 4, model cpsam) as a tool, one plane: the generalist model, which needs no diameter.
    Offered only where Cellpose 4 is installed (examples/cellpose-sam), since Cellpose 3's models are gone there.
    Measured on PhC-C2DL-PSC 01, every 30th frame, as it is: DET 0.957 against cyto3's 0.920, half the merges."""
    _model = None
    _lock = threading.Lock()

    def __init__(self):
        self.id = 'cellpose.cpsam'
        self.manifest = {
            'id': self.id,
            'description': "Cellpose-SAM (Cellpose 4, model cpsam): segments whole cells or nuclei, one detection per "
                           "mask, in any 2D modality; no diameter needed. One plane.",
            'role': 'DETECT',
            'parameters': [
                {'name': 'flow_threshold', 'type': 'NUMBER', 'description': 'mask quality cut-off',
                 'default': 0.4, 'min': 0, 'max': 3},
            ],
            'applicability': {'requires': {'volumetric': False}, 'score': 0.85,
                              'reason': 'cells or nuclei in any 2D modality, one plane'},
        }

    def run(self, image, params, frame):
        from cellpose import models
        flow = float(params.get('flow_threshold', 0.4))
        with self._lock:
            if CellposeSAM._model is None:
                import torch  # CUDA, or Apple's MPS (cyto3 keeps _gpu(): CUDA only, as it was measured)
                CellposeSAM._model = models.CellposeModel(
                    gpu=torch.cuda.is_available() or torch.backends.mps.is_available())
            # A copy: the request's array is read-only, and Cellpose 4 normalises in place.
            masks, _, _ = CellposeSAM._model.eval(np.array(image, copy=True), flow_threshold=flow)
        return _detections(masks), f'cellpose-sam: {int(masks.max())} masks'


def _cellpose_major():
    try:
        from importlib.metadata import version
        return int(version('cellpose').split('.')[0])
    except Exception:
        return 0


def available_tools():
    tools = {}
    if _have('cellpose') and _cellpose_major() >= 4:
        t = CellposeSAM()
        tools[t.id] = t
    elif _have('cellpose'):
        for t in (Cellpose('nuclei'), Cellpose('cyto3'), Cellpose('nuclei', volumetric=True)):
            tools[t.id] = t
    if _have('stardist'):
        for name, fluo in (('2D_versatile_fluo', True), ('2D_versatile_he', False)):
            t = StarDist(name, fluo)
            tools[t.id] = t
    return tools


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
        if self.path.rstrip('/') == '/tools':
            self._send(200, {'protocol': PROTOCOL, 'tools': [t.manifest for t in TOOLS.values()]})
        else:
            self._send(404, {'error': 'unknown path ' + self.path})

    def do_POST(self):
        parts = self.path.strip('/').split('/')
        if len(parts) != 3 or parts[0] != 'tools' or parts[2] != 'run' or parts[1] not in TOOLS:
            return self._send(404, {'error': 'unknown tool or path ' + self.path})
        try:
            req = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            f = req['frame']
            if f.get('dtype', 'float32') != 'float32':
                raise ValueError('only float32 frames')
            image = np.frombuffer(base64.b64decode(f['data']), dtype='<f4').reshape(f['shape'])
            if image.ndim == 3 and image.shape[0] == 1:
                image = image[0]
            tool = TOOLS[parts[1]]
            if image.ndim != (3 if getattr(tool, 'volumetric', False) else 2):
                raise ValueError('%s takes %s; got shape %s' % (
                    tool.id, 'a z-stack' if getattr(tool, 'volumetric', False) else 'one plane', image.shape))
            dets, msg = tool.run(image, req.get('params') or {}, f)
            self._send(200, {'detections': dets, 'message': msg})
        except Exception as e:  # report, do not crash the server
            self._send(500, {'error': f'{type(e).__name__}: {e}'})

    def log_message(self, fmt, *args):
        print('%s - %s' % (self.address_string(), fmt % args))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--port', type=int, default=8765)
    ap.add_argument('--host', default='127.0.0.1',
                    help='address to listen on (default 127.0.0.1, this machine only; 0.0.0.0 = every network interface: anyone who can reach this machine can then send it images to segment, so keep it behind a firewall or a trusted network)')
    a = ap.parse_args()
    TOOLS.update(available_tools())
    print(f'{PROTOCOL}: offering {", ".join(TOOLS) or "nothing (install cellpose or stardist)"} on {a.host}:{a.port}')
    ThreadingHTTPServer((a.host, a.port), Handler).serve_forever()


if __name__ == '__main__':
    main()
