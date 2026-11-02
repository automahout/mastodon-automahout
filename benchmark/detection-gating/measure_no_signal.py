"""Where to put the no-signal deletion ceiling.

The pass deletes a detection whose interior reads no more than the ceiling. Two
wrong answers preceded the measured one, and both are in the table this prints:
1.5 sigma never touched a real cell but left most background standing, and the
correction for it abandoned sigma for a fraction of the climb to the image's
bright tail -- 20.3 sigma on this dataset.
"""
import numpy as np

CELLS, BACKGROUND = 'cells.npy', 'background.npy'
BG_MEAN, BG_SD = 33044.0, 7.0          # the dataset the user is running
BRIGHT_TAIL = 33992.0                  # ImageProfile.brightTail, 99.5th pct of pixels
MARGIN_FRACTION = 0.15                 # NO_SIGNAL_MARGIN_FRACTION


def main():
    c = np.load(CELLS)[:, 0]
    b = np.load(BACKGROUND)[:, 0]
    print(f"cells n={len(c)}  background n={len(b)}\n")
    print(f"cells      standout p01/p05/p50 = {np.percentile(c, 1):.2f} / "
          f"{np.percentile(c, 5):.2f} / {np.percentile(c, 50):.2f} sigma")
    print(f"background standout p95/p99/max = {np.percentile(b, 95):.2f} / "
          f"{np.percentile(b, 99):.2f} / {b.max():.2f} sigma\n")

    tail_sigma = MARGIN_FRACTION * (BRIGHT_TAIL - BG_MEAN) / BG_SD
    print(f"{'delete at (sigma)':<20}{'cells lost':>12}{'background caught':>20}"
          f"{'grey level':>13}")
    for k in (1.5, 1.75, 2.0, 2.5, 3.0, 4.0, tail_sigma):
        note = '   <- the bright-tail anchor' if k == tail_sigma else ''
        print(f"{k:<20.2f}{100 * (c < k).mean():>11.2f}%{100 * (b < k).mean():>19.1f}%"
              f"{BG_MEAN + k * BG_SD:>13.0f}{note}")


if __name__ == '__main__':
    main()
