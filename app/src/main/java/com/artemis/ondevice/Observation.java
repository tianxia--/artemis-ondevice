package com.artemis.ondevice;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.view.Display;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One observation: a downscaled JPEG screenshot plus the indexed element list
 * ({@code [i] Text: '...' | Bounds: ...}, the format Artemis's Flash agent reads).
 */
final class Observation {

    /** Longest screenshot side sent to the model. */
    static final int MAX_SIDE = 1024;
    static final int JPEG_QUALITY = 70;
    static final int MAX_ELEMENTS = 120;
    private static final int ERROR_INTERVAL_TOO_SHORT = 3;

    private static final ExecutorService SHOT_EXECUTOR = Executors.newSingleThreadExecutor();

    static final class Element {
        final int left, top, right, bottom;
        final String label;
        Element(int l, int t, int r, int b, String label) {
            left = l; top = t; right = r; bottom = b; this.label = label;
        }
        int cx() { return (left + right) / 2; }
        int cy() { return (top + bottom) / 2; }
    }

    byte[] jpeg;
    String screenshotError;
    int screenW, screenH;
    String packageName = "";
    final List<Element> elements = new ArrayList<>();
    long elapsedMs;

    static Observation capture(AgentAccessibilityService service) {
        long t0 = System.currentTimeMillis();
        Observation o = new Observation();
        o.packageName = service.currentPackage();

        final AtomicReference<Bitmap> bmp = new AtomicReference<>();
        final AtomicInteger err = new AtomicInteger();
        final CountDownLatch latch = new CountDownLatch(1);
        requestShot(service, bmp, err, latch, true);

        android.graphics.Rect screen = service.getSystemService(android.view.WindowManager.class)
                .getMaximumWindowMetrics().getBounds();
        o.screenW = screen.width();
        o.screenH = screen.height();
        o.elements.addAll(UiTree.collect(service, screen, MAX_ELEMENTS));

        try {
            latch.await(3000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Bitmap b = bmp.get();
        if (b != null) {
            o.jpeg = encode(b);
            b.recycle();
        } else {
            o.screenshotError = err.get() != 0 ? "takeScreenshot error " + err.get() : "screenshot timed out";
        }
        o.elapsedMs = System.currentTimeMillis() - t0;
        return o;
    }


    /** Element list with bounds normalized to the 0-1000 scale the model uses for coordinates. */
    String elementsText() {
        if (elements.isEmpty()) return "(no elements found)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < elements.size(); i++) {
            Element e = elements.get(i);
            sb.append('[').append(i).append("] ").append(e.label).append(" | Bounds: [")
                    .append(nx(e.left)).append(',').append(ny(e.top)).append("][")
                    .append(nx(e.right)).append(',').append(ny(e.bottom)).append("]\n");
        }
        return sb.toString();
    }

    int nx(int px) { return screenW <= 0 ? 0 : Math.round(px * 1000f / screenW); }
    int ny(int px) { return screenH <= 0 ? 0 : Math.round(px * 1000f / screenH); }

    private static byte[] encode(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        float scale = Math.min(1f, MAX_SIDE / (float) Math.max(w, h));
        Bitmap scaled = scale < 1f
                ? Bitmap.createScaledBitmap(src, Math.round(w * scale), Math.round(h * scale), true)
                : src;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
        if (scaled != src) scaled.recycle();
        return out.toByteArray();
    }

    /** takeScreenshot is rate-limited (~3/s); one retry after the interval, like the helper. */
    private static void requestShot(final AccessibilityService service, final AtomicReference<Bitmap> bmp,
                                    final AtomicInteger err, final CountDownLatch latch, final boolean retry) {
        service.takeScreenshot(Display.DEFAULT_DISPLAY, SHOT_EXECUTOR,
                new AccessibilityService.TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(AccessibilityService.ScreenshotResult result) {
                        try (HardwareBuffer buf = result.getHardwareBuffer()) {
                            Bitmap hw = Bitmap.wrapHardwareBuffer(buf, result.getColorSpace());
                            if (hw != null) {
                                bmp.set(hw.copy(Bitmap.Config.ARGB_8888, false));
                                hw.recycle();
                            }
                        } catch (Throwable t) {
                            android.util.Log.w(AgentService.TAG, "screenshot copy failed", t);
                        } finally {
                            latch.countDown();
                        }
                    }

                    @Override
                    public void onFailure(int code) {
                        err.set(code);
                        if (retry && code == ERROR_INTERVAL_TOO_SHORT) {
                            SHOT_EXECUTOR.execute(new Runnable() {
                                @Override public void run() {
                                    try { Thread.sleep(350L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                                    requestShot(service, bmp, err, latch, false);
                                }
                            });
                            return;
                        }
                        latch.countDown();
                    }
                });
    }
}
