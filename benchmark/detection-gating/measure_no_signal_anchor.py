"""Where the no-signal ceiling should be anchored, and at what coefficient.

The pass deleted NOTHING in production. Two errors, the second hiding the first:
NO_SIGNAL_SIGMA was chosen on a fixture of truth cells and synthetic background
peaks, and the ceiling was anchored on estimateBackgroundStats, which avoids
positions near existing spots and so reports the frame's EMPTIEST regions --
about 3.8 sigma below its own median. A ceiling meant to sit 2.5 sigma above the
background sat a sigma below it.

This re-measures on the detector's OWN detections, labelled against CTC silver
truth, reproducing the pass exactly: the radius the extent fix now produces, the
13 planar interior offsets, the 5 core offsets, and the rule that BOTH the
interior median and the core median must fall under the ceiling.

Run a window with -Dautomahout.splitDump=/tmp/split-fixture.tsv first.
"""
import sys, pathlib
_d = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(_d/'cell-extent')); sys.path.insert(0, str(_d/'seed-to-object'))
import numpy as np
from PIL import Image
from measure_extent import RAW, SEG, ray
from label_dump import load, VOXEL_UM, truth_centroids

D = 0.6/np.sqrt(2)
OFF = [(0,0)] + [(s*r,0) for r in (0.5,0.8) for s in (-1,1)] \
               + [(0,s*r) for r in (0.5,0.8) for s in (-1,1)] \
               + [(sx*D,sy*D) for sx in (-1,1) for sy in (-1,1)]
CORE = [o for o in OFF if np.hypot(*o) <= 0.5 + 1e-9]
MIN_F, MAX_F, DEAD = 0.5, 1.4, 0.1
def ef(meas, seed):
    if not np.isfinite(meas) or meas<=0 or seed<=0: return 0.0
    f=max(MIN_F,min(MAX_F,meas/seed)); return 0.0 if abs(f-1.0)<DEAD else f
def at(img,y,x):
    H,W=img.shape
    return img[min(max(int(round(y)),0),H-1), min(max(int(round(x)),0),W-1)]

rows=load('/tmp/split-fixture.tsv'); seen=set(); uniq=[]
for r in rows:
    k=(r['t'],r['x'],r['y'],r['radius'])
    if k not in seen: seen.add(k); uniq.append(r)
by_t={}
for r in uniq: by_t.setdefault(int(r['t']),[]).append(r)

recs=[]   # (frame_med, sigma, med_old, core_old, med_new, core_new, is_cell)
for t,rs in sorted(by_t.items()):
    f=RAW/f't{t:03d}.tif'
    if not f.exists(): continue
    cen=truth_centroids('01',t)
    if cen is None or not len(cen): continue
    img=np.array(Image.open(f)).astype('float32')
    med=float(np.median(img)); sig=max(1.4826*float(np.median(np.abs(img-med))),1e-9)
    for r in rs:
        x,y=float(r['x'])/VOXEL_UM,float(r['y'])/VOXEL_UM; seed=float(r['radius'])/VOXEL_UM
        e=ray(img,int(round(y)),int(round(x)),med,sig,5.0,n_rays=8)
        fac=ef(e,seed); new = seed if fac==0.0 else fac*seed
        def prof(rad):
            v=[at(img,y+oy*rad,x+ox*rad) for ox,oy in OFF]
            c=[at(img,y+oy*rad,x+ox*rad) for ox,oy in CORE]
            return float(np.median(v)), float(np.median(c))
        mo,co=prof(seed); mn,cn=prof(new)
        d=np.hypot(cen[:,0]-y, cen[:,1]-x)
        recs.append((med,sig,mo,co,mn,cn,bool((d<=seed).sum())))
recs=np.array(recs,dtype=object)
med=np.array([r[0] for r in recs]); sig=np.array([r[1] for r in recs])
cell=np.array([r[6] for r in recs])
print(f"{len(recs)} detections, {cell.sum()} on a truth cell, {(~cell).sum()} on none\n")
BG_OFFSET = -50.0   # where estimateBackgroundStats lands, measured
for label, anchor in (("production anchor (frame median - 50)", med+BG_OFFSET),
                      ("frame's own median", med)):
    for radlbl, (mi,ci) in (("radius as-is",(2,3)), ("radius after the extent fix",(4,5))):
        print(f"{label}, {radlbl}")
        print(f"{'k':>6}{'caught (no cell)':>19}{'lost (real cell)':>19}{'deleted overall':>17}")
        m=np.array([r[mi] for r in recs]); c=np.array([r[ci] for r in recs])
        for k in (0.0,0.5,1.0,1.25,1.5,2.0,2.5):
            ceil=anchor + k*sig
            d=(m<=ceil)&(c<=ceil)
            print(f"{k:>6.1f}{100*d[~cell].mean():>18.1f}%{100*d[cell].mean():>18.1f}%"
                  f"{100*d.mean():>16.1f}%")
        print()
