"""Which background the cell-extent measure should be anchored on.

measure_extent.py calibrated CELL_EXTENT_SIGMA=5 against the FRAME's own median
and reported a radius 1.02x the truth. Production delivered 1.31x. The whole gap
is the anchor: measuredCellRadius used estimateBackgroundStats, which
deliberately avoids positions near existing spots and so reports the EMPTIEST
regions -- about 50 grey levels (3.8 sigma) below the frame median on this movie.
Every ray then walks that much further before it crosses.

This scores the actual annotation, against CTC silver truth, at the detector's
OWN positions from the split dump (not at truth centroids, which is the other
thing the original benchmark did differently and which costs 6 points on its own).

Run a window with -Dautomahout.splitDump=/tmp/split-fixture.tsv first.
"""
import sys
import pathlib; _d=pathlib.Path(__file__).parent; sys.path.insert(0,str(_d)); sys.path.insert(0,str(_d.parent/'seed-to-object'))
import numpy as np
from PIL import Image
from measure_extent import RAW, SEG, ray
from label_dump import load, VOXEL_UM
rows=load('/tmp/split-fixture.tsv'); seen=set(); uniq=[]
for r in rows:
    k=(r['t'],r['x'],r['y'],r['radius'])
    if k not in seen: seen.add(k); uniq.append(r)
by_t={}
for r in uniq: by_t.setdefault(int(r['t']),[]).append(r)
MIN_F,MAX_F,DEAD=0.5,1.4,0.1
def ef(meas,seed):
    if not np.isfinite(meas) or meas<=0 or seed<=0: return 0.0
    f=max(MIN_F,min(MAX_F,meas/seed)); return 0.0 if abs(f-1.0)<DEAD else f
opts=[('as-is (production today)',None)]+[(f'frame median + {k:g}sigma',k) for k in (2.0,3.0,4.0,5.0)]
out={n:[] for n,_ in opts}
for t,rs in sorted(by_t.items()):
    f=RAW/f't{t:03d}.tif'; segf=SEG/f'man_seg{t:03d}.tif'
    if not f.exists() or not segf.exists(): continue
    img=np.array(Image.open(f)).astype('float32'); seg=np.array(Image.open(segf))
    med=float(np.median(img)); sig=max(1.4826*float(np.median(np.abs(img-med))),1e-9)
    H,W=seg.shape; yy,xx=np.mgrid[0:H,0:W]
    area={int(l):int((seg==l).sum()) for l in np.unique(seg) if l}
    for r in rs:
        x,y=float(r['x'])/VOXEL_UM,float(r['y'])/VOXEL_UM; seed=float(r['radius'])/VOXEL_UM
        iy,ix=int(round(y)),int(round(x))
        if not(0<=iy<H and 0<=ix<W): continue
        lab=int(seg[iy,ix])
        if not lab or area.get(lab,0)<30: continue
        m=seg==lab; a=area[lab]
        d2=(yy-y)**2+(xx-x)**2
        for name,k in opts:
            rad = seed if k is None else ( (lambda e: seed if ef(e,seed)==0.0 else ef(e,seed)*seed)(ray(img,iy,ix,med,sig,k,n_rays=8)) )
            if not np.isfinite(rad) or rad<=0: continue
            disc=d2<=rad*rad; n=int(disc.sum())
            if n==0: continue
            inter=int((disc&m).sum())
            p=inter/n; rc=inter/a
            out[name].append(p*rc/(p+rc-p*rc) if p+rc-p*rc>0 else 0.0)
print(f"{'level for the extent measure':<32}{'IoU p50':>9}{'IoU p10':>9}")
for name,_ in opts:
    a=np.array(out[name])
    print(f"{name:<32}{np.median(a):>9.2f}{np.percentile(a,10):>9.2f}")
