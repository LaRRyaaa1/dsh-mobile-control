package com.dsh.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ScanActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    private static final String TAG = "DshScan";
    private static final int REQ_CAMERA = 2001;

    private SurfaceView surfaceView;
    private SurfaceHolder holder;
    private Camera camera;
    private MultiFormatReader reader;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean decoding;
    private byte[] frame;
    private int frameW;
    private int frameH;
    private volatile boolean finished;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        initReader();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surfaceView = new SurfaceView(this);
        holder = surfaceView.getHolder();
        holder.addCallback(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView hint = new TextView(this);
        hint.setText(getString(R.string.scan_hint));
        hint.setTextColor(0xFFFFFFFF);
        hint.setTextSize(14);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(18), dp(12), dp(18), dp(12));
        hint.setBackgroundColor(0x99000000);
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        hintLp.bottomMargin = dp(72);
        root.addView(hint, hintLp);

        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextColor(Color.WHITE);
        cancel.setTextSize(15);
        cancel.setPadding(dp(18), dp(10), dp(18), dp(10));
        cancel.setBackgroundColor(0x99000000);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        FrameLayout.LayoutParams cancelLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        cancelLp.leftMargin = dp(12);
        cancelLp.topMargin = dp(12);
        root.addView(cancel, cancelLp);

        setContentView(root);
        requestCameraIfNeeded();
    }

    private void initReader() {
        reader = new MultiFormatReader();
        EnumMap<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        List<BarcodeFormat> formats = new ArrayList<>();
        formats.add(BarcodeFormat.QR_CODE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, formats);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        reader.setHints(hints);
    }

    private void requestCameraIfNeeded() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_CAMERA) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (holder != null && holder.getSurface() != null && holder.getSurface().isValid()) {
                openCamera();
            }
        } else {
            Toast.makeText(this, "需要相机权限才能扫码；也可以手动粘贴配对链接",
                    Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        openCamera();
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
        // Preview size is managed by Camera.Parameters.
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        releaseCamera();
    }

    private void openCamera() {
        if (camera != null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            camera = Camera.open();
        } catch (Exception e) {
            Log.w(TAG, "Camera.open failed", e);
            Toast.makeText(this, "无法打开相机：" + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        try {
            Camera.Parameters p = camera.getParameters();
            List<Integer> formats = p.getSupportedPreviewFormats();
            if (formats != null && formats.contains(android.graphics.ImageFormat.NV21)) {
                p.setPreviewFormat(android.graphics.ImageFormat.NV21);
            }
            Camera.Size size = chooseSize(p.getSupportedPreviewSizes());
            if (size != null) p.setPreviewSize(size.width, size.height);
            List<String> focusModes = p.getSupportedFocusModes();
            if (focusModes != null) {
                if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                    p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                    p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
                } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                    p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                }
            }
            camera.setParameters(p);
            camera.setDisplayOrientation(90);
            camera.setPreviewDisplay(holder);
            camera.startPreview();
            frameW = p.getPreviewSize().width;
            frameH = p.getPreviewSize().height;
            int bits = android.graphics.ImageFormat.getBitsPerPixel(p.getPreviewFormat());
            if (bits <= 0) bits = 12;
            int bytes = frameW * frameH * bits / 8;
            frame = new byte[bytes];
            camera.addCallbackBuffer(new byte[bytes]);
            camera.setPreviewCallbackWithBuffer(this);
        } catch (Exception e) {
            Log.w(TAG, "camera setup failed", e);
            Toast.makeText(this, "相机初始化失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            releaseCamera();
            finish();
        }
    }

    private Camera.Size chooseSize(List<Camera.Size> sizes) {
        if (sizes == null || sizes.isEmpty()) return null;
        Camera.Size best = sizes.get(0);
        long bestScore = Long.MAX_VALUE;
        for (Camera.Size s : sizes) {
            long pixels = (long) s.width * s.height;
            if (pixels < 640L * 480L || pixels > 1920L * 1080L) continue;
            long score = Math.abs(pixels - 1280L * 720L);
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera cam) {
        if (cam == null) return;
        if (finished) {
            cam.addCallbackBuffer(data);
            return;
        }
        if (decoding || frame == null) {
            cam.addCallbackBuffer(data);
            return;
        }
        int len = Math.min(data.length, frame.length);
        System.arraycopy(data, 0, frame, 0, len);
        decoding = true;
        final int w = frameW;
        final int h = frameH;
        cam.addCallbackBuffer(data);
        decoder.execute(new Runnable() {
            @Override public void run() {
                Result r = null;
                try {
                    PlanarYUVLuminanceSource source =
                            new PlanarYUVLuminanceSource(frame, w, h, 0, 0, w, h, false);
                    BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
                    r = reader.decodeWithState(bitmap);
                } catch (Exception e) {
                    // NotFoundException is the normal case for most frames.
                } finally {
                    reader.reset();
                    decoding = false;
                }
                final String text = r == null ? null : r.getText();
                if (text != null) {
                    main.post(new Runnable() {
                        @Override public void run() { deliver(text); }
                    });
                }
            }
        });
    }

    private void deliver(String text) {
        if (finished) return;
        if (!(text.startsWith("http://") || text.startsWith("https://")
                || text.startsWith("dshmobile://"))) {
            Toast.makeText(this, "这不是 DSH 配对二维码", Toast.LENGTH_SHORT).show();
            return;
        }
        finished = true;
        releaseCamera();
        Intent out = new Intent();
        out.putExtra("result", text);
        setResult(RESULT_OK, out);
        finish();
    }

    private void releaseCamera() {
        Camera c = camera;
        camera = null;
        if (c != null) {
            try { c.setPreviewCallbackWithBuffer(null); } catch (Exception ignored) {}
            try { c.stopPreview(); } catch (Exception ignored) {}
            try { c.release(); } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        releaseCamera();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (holder != null && holder.getSurface() != null && holder.getSurface().isValid()) {
            openCamera();
        }
    }

    @Override
    protected void onDestroy() {
        finished = true;
        releaseCamera();
        decoder.shutdownNow();
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
