# Records the game window with ffmpeg and tracks the centroid of pink (pig) pixels per frame.
# usage: python3 devtools/track.py <out.npy> [seconds]
import subprocess, sys, numpy as np
out = sys.argv[1]; secs = sys.argv[2] if len(sys.argv) > 2 else "4"
raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-f", "x11grab", "-framerate", "60", "-video_size", "854x400",
	"-i", ":99+213,120", "-t", secs, "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], capture_output=True).stdout
frames = np.frombuffer(raw, np.uint8).reshape(-1, 400, 854, 3).astype(int)
cs = []
for f in frames:
	m = (f[:, :, 0] > 180) & (f[:, :, 1] < 160) & (f[:, :, 2] > 130) & (f[:, :, 0] - f[:, :, 1] > 50)
	ys, xs = np.nonzero(m)
	cs.append((xs.mean(), ys.mean(), m.sum()) if len(xs) > 50 else (np.nan, np.nan, 0))
cs = np.array(cs); np.save(out, cs)
x = cs[:, 0]; dx = np.abs(np.diff(x))
dup = (np.all(frames[1:] == frames[:-1], axis=(1, 2, 3))).sum()
print(f"{len(frames)} frames, {dup} exact repeats; pig centroid steps: moving in {np.sum(dx > 0.05)} of {len(dx)} frame steps, "
	f"mean |step| {np.nanmean(dx):.3f} px, max |step| {np.nanmax(dx):.2f} px, step std {np.nanstd(dx):.3f}")
