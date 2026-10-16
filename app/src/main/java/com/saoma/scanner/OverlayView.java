package com.saoma.scanner;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 覆盖在取景框上的绘制层：
 *  - 单个条码：绿色框
 *  - 多个条码：红色框，点击红框手动确认
 */
public class OverlayView extends View {

    public static class Detection {
        public final String value;
        public final RectF box; // 视图坐标
        public final int format; // ML Kit 的条码格式
        /** 是否被判定为"将自动记录"的那个（画绿框并标注） */
        public boolean auto;

        public Detection(String value, RectF box, int format) {
            this.value = value;
            this.box = box;
            this.format = format;
        }
    }

    public interface OnDetectionTap {
        void onTap(Detection detection);
    }

    private final List<Detection> detections = new ArrayList<>();
    private boolean multi;
    private OnDetectionTap tapListener;

    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();

    private float density;

    public OverlayView(Context context) {
        this(context, null);
    }

    public OverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        density = getResources().getDisplayMetrics().density;

        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(3.5f * density);

        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextSize(13f * density);
        labelPaint.setFakeBoldText(true);

        labelBgPaint.setColor(0xE6000000);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1.5f * density);
        borderPaint.setColor(0x55FFFFFF);
    }

    public void setDetections(List<Detection> list, boolean multi) {
        detections.clear();
        if (list != null) {
            detections.addAll(list);
        }
        this.multi = multi;
        invalidate();
    }

    public void setTapListener(OnDetectionTap listener) {
        this.tapListener = listener;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }

        // 取景框边框
        float inset = 2f * density;
        tmp.set(inset, inset, w - inset, h - inset);
        canvas.drawRect(tmp, borderPaint);

        for (Detection d : detections) {
            if (d == null || d.box == null) {
                continue;
            }
            int color = d.auto ? 0xFF00C853 : (multi ? 0xFFFF3B30 : 0xFF00C853);
            boxPaint.setColor(color);

            RectF b = new RectF(d.box);
            if (b.width() < 8f) {
                b.left = b.centerX() - 4f;
                b.right = b.centerX() + 4f;
            }
            if (b.height() < 8f) {
                b.top = b.centerY() - 4f;
                b.bottom = b.centerY() + 4f;
            }
            b.inset(-7f * density, -7f * density);
            canvas.drawRoundRect(b, 10f, 10f, boxPaint);

            String label = d.value == null ? "" : d.value;
            if (label.length() > 20) {
                label = label.substring(0, 20) + "…";
            }
            if (d.auto) {
                label = label + "  · 自动";
            }
            if (label.isEmpty()) {
                continue;
            }
            float textW = labelPaint.measureText(label);
            float pad = 7f * density;
            float lineH = labelPaint.getTextSize() + pad * 2f;
            float lx = b.left;
            if (lx + textW + pad * 2f > w) {
                lx = Math.max(0f, w - textW - pad * 2f);
            }
            float ly = b.top - lineH - 4f * density;
            if (ly < 0f) {
                ly = b.top + 4f * density;
            }
            canvas.drawRoundRect(new RectF(lx, ly, lx + textW + pad * 2f, ly + lineH),
                    7f * density, 7f * density, labelBgPaint);
            canvas.drawText(label, lx + pad,
                    ly + lineH / 2f - (labelPaint.ascent() + labelPaint.descent()) / 2f,
                    labelPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            float x = event.getX();
            float y = event.getY();
            Detection best = null;
            float bestArea = Float.MAX_VALUE;
            for (Detection d : detections) {
                if (d == null || d.box == null) {
                    continue;
                }
                RectF hit = new RectF(d.box);
                hit.inset(-24f * density, -24f * density);
                if (hit.contains(x, y)) {
                    float area = Math.max(1f, d.box.width()) * Math.max(1f, d.box.height());
                    if (area < bestArea) {
                        bestArea = area;
                        best = d;
                    }
                }
            }
            if (best != null && tapListener != null) {
                tapListener.onTap(best);
                return true;
            }
        }
        return true;
    }
}
