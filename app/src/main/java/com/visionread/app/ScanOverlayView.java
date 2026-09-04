package com.visionread.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

public class ScanOverlayView extends View {

    private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scanLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float scanLineY = 0f;
    private float flashAlpha = 0f;
    private boolean scanning = false;
    private ValueAnimator scanAnimator;
    private ValueAnimator flashAnimator;

    public ScanOverlayView(Context context) { super(context); init(); }
    public ScanOverlayView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public ScanOverlayView(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }

    private void init() {
        cornerPaint.setColor(Color.parseColor("#00E5FF"));
        cornerPaint.setStyle(Paint.Style.STROKE);
        cornerPaint.setStrokeWidth(6f);
        cornerPaint.setStrokeCap(Paint.Cap.ROUND);
        scanLinePaint.setStyle(Paint.Style.FILL);
        flashPaint.setColor(Color.parseColor("#00FF88"));
        flashPaint.setStyle(Paint.Style.STROKE);
        flashPaint.setStrokeWidth(6f);
    }

    public void setScanning(boolean active) {
        this.scanning = active;
        if (active) startScanAnimation(); else stopScanAnimation();
    }

    public void flashDetected() {
        if (flashAnimator != null) flashAnimator.cancel();
        flashAnimator = ValueAnimator.ofFloat(1f, 0f);
        flashAnimator.setDuration(800);
        flashAnimator.addUpdateListener(a -> { flashAlpha = (float) a.getAnimatedValue(); invalidate(); });
        flashAnimator.start();
    }

    private void startScanAnimation() {
        if (scanAnimator != null) scanAnimator.cancel();
        scanAnimator = ValueAnimator.ofFloat(0.15f, 0.85f);
        scanAnimator.setDuration(2000);
        scanAnimator.setInterpolator(new LinearInterpolator());
        scanAnimator.setRepeatCount(ValueAnimator.INFINITE);
        scanAnimator.setRepeatMode(ValueAnimator.REVERSE);
        scanAnimator.addUpdateListener(a -> { scanLineY = (float) a.getAnimatedValue(); invalidate(); });
        scanAnimator.start();
    }

    private void stopScanAnimation() {
        if (scanAnimator != null) { scanAnimator.cancel(); scanAnimator = null; }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!scanning) return;
        float w = getWidth(), h = getHeight();
        float margin = 48f, arm = 60f;
        float l = margin, r = w - margin, t = margin, b = h - margin;

        canvas.drawLine(l, t + arm, l, t, cornerPaint);
        canvas.drawLine(l, t, l + arm, t, cornerPaint);
        canvas.drawLine(r - arm, t, r, t, cornerPaint);
        canvas.drawLine(r, t, r, t + arm, cornerPaint);
        canvas.drawLine(l, b - arm, l, b, cornerPaint);
        canvas.drawLine(l, b, l + arm, b, cornerPaint);
        canvas.drawLine(r - arm, b, r, b, cornerPaint);
        canvas.drawLine(r, b - arm, r, b, cornerPaint);

        float lineY = t + (b - t) * scanLineY;
        LinearGradient gradient = new LinearGradient(l, lineY, r, lineY,
                new int[]{Color.TRANSPARENT, Color.parseColor("#00E5FF"), Color.TRANSPARENT},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        scanLinePaint.setShader(gradient);
        canvas.drawRect(l, lineY - 1.5f, r, lineY + 1.5f, scanLinePaint);

        if (flashAlpha > 0f) {
            flashPaint.setAlpha((int)(flashAlpha * 255));
            canvas.drawRoundRect(new RectF(margin/2, margin/2, w-margin/2, h-margin/2), 32f, 32f, flashPaint);
        }
    }
}
