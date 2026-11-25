# Records the window during a steady mouse turn and measures the horizontal image shift between consecutive distinct
# frames (phase correlation on a strip). Smooth turning = steady shifts. usage: python3 devtools/pantrack.py [secs]
import subprocess, sys, threading, time, numpy as np
secs = sys.argv[1] if len(sys.argv) > 1 else "4"
def turn():
	time.sleep(0.5)
	subprocess.run(["python3", "devtools/xin.py", "rel:2:0:%d" % int(float(secs) * 50)])
threading.Thread(target=turn).start()
raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-f", "x11grab", "-framerate", "60", "-video_size", "854x160",
	"-i", ":99+213,200", "-t", secs, "-f", "rawvideo", "-pix_fmt", "gray", "-"], capture_output=True).stdout
f = np.frombuffer(raw, np.uint8).reshape(-1, 160, 854).astype(float)
shifts = []
prev = None
for img in f[30:]:
	if prev is not None and np.array_equal(img, prev):
		continue
	if prev is not None:
		a = np.fft.fft2(prev - prev.mean()); b = np.fft.fft2(img - img.mean())
		r = np.fft.ifft2(a * np.conj(b) / (np.abs(a * np.conj(b)) + 1e-9)).real
		dy, dx = np.unravel_index(np.argmax(r), r.shape)
		if dx > 427: dx -= 854
		shifts.append(dx)
	prev = img
s = np.array(shifts)
print(f"{len(s)} distinct frames; shift per frame: mean {s.mean():.2f} px, std {s.std():.2f}, zero-shift frames {np.sum(s == 0)}, "
	f"median {np.median(s):.1f}, p10 {np.percentile(s, 10):.1f}, p90 {np.percentile(s, 90):.1f}")
