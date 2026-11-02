"""How well the shape the pipeline annotates matches the cell under it.

The user's point, measured: human annotation follows the contour, the pipeline's
includes margin, and at a constriction between two touching cells that margin
reaches into the neck and carries dark structure into what is taught as "cell".

Run a window with -Dautomahout.splitDump=/tmp/split-fixture.tsv first. Radii in
that dump are recorded in the split pass, i.e. AFTER resizeToMeasuredExtent, so
this measures what training actually receives.

Four shapes, scored against CTC silver truth:
  as-is                        what the pipeline annotates today
  right-size circle            the same circle at the cell's equivalent radius
  ellipse @ detection centre   the cell's own axes, placed where the detector put it
  ellipse @ cell centre        the same, perfectly centred -- the ceiling

The last two are UPPER BOUNDS: they use the truth cell's own axes, which a fit
from the image cannot know. A real fit scores below them.
"""
import sys; sys.path.insert(0, str(__import__('pathlib').Path(__file__).parent.parent / 'seed-to-object'))
import numpy as np
from PIL import Image
from label_dump import load, ROOT, VOXEL_UM
rows=load('/tmp/split-fixture.tsv'); seen=set(); uniq=[]
for r in rows:
    k=(r['t'],r['x'],r['y'],r['radius'])
    if k not in seen: seen.add(k); uniq.append(r)
by_t={}
for r in uniq: by_t.setdefault(int(r['t']),[]).append(r)
res={k:[] for k in ['as-is','right-size circle','ellipse @ detection centre','ellipse @ cell centre']}
rec={k:[] for k in res}
for t,rs in sorted(by_t.items()):
    f=ROOT/'01_ST'/'SEG'/f'man_seg{t:03d}.tif'
    if not f.exists(): continue
    seg=np.array(Image.open(f)); H,W=seg.shape
    yy,xx=np.mgrid[0:H,0:W]
    geo={}
    for lab in np.unique(seg):
        if lab==0: continue
        m=seg==lab; a=int(m.sum())
        if a<30: continue
        ys,xs=np.nonzero(m); cy,cx=ys.mean(),xs.mean()
        c=np.cov(np.vstack([ys-cy,xs-cx]))
        w,v=np.linalg.eigh(c); o=np.argsort(w)[::-1]; w=w[o]; v=v[:,o]
        geo[lab]=(a,np.sqrt(a/np.pi),cy,cx,2*np.sqrt(max(w[0],1e-9)),2*np.sqrt(max(w[1],1e-9)),v)
    for r in rs:
        x,y=float(r['x'])/VOXEL_UM,float(r['y'])/VOXEL_UM; rad=float(r['radius'])/VOXEL_UM
        row,col=int(round(y)),int(round(x))
        if not(0<=row<H and 0<=col<W): continue
        lab=seg[row,col]
        if lab==0 or lab not in geo: continue
        a,req,cy,cx,A,B,v=geo[lab]; m=seg==lab
        def score(mask,name):
            n=int(mask.sum())
            if n==0: return
            inter=int((mask&m).sum())
            res[name].append(inter/n)          # precision: of what we annotate, how much is cell
            rec[name].append(inter/a)          # recall: of the cell, how much we annotate
        score((yy-y)**2+(xx-x)**2<=rad*rad, 'as-is')
        score((yy-y)**2+(xx-x)**2<=req*req, 'right-size circle')
        for name,(oy,ox) in [('ellipse @ detection centre',(y,x)),('ellipse @ cell centre',(cy,cx))]:
            dy,dx=yy-oy,xx-ox
            u=v[0,0]*dy+v[1,0]*dx; w2=v[0,1]*dy+v[1,1]*dx
            score(u*u/(A*A)+w2*w2/(B*B)<=1.0, name)
print(f"{len(res['as-is'])} detections on a truth cell\n")
print(f"{'annotation shape':<28}{'precision p50':>14}{'recall p50':>12}{'IoU p50':>10}")
for k in res:
    p=np.array(res[k]); rc=np.array(rec[k])
    iou=p*rc/(p+rc-p*rc)
    print(f"{k:<28}{np.median(p):>14.2f}{np.median(rc):>12.2f}{np.median(iou):>10.2f}")
