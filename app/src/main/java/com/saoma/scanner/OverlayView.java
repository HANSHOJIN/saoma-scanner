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
 *  - 条码框：绿框（将自动记录 / 单个）、红框（多码需点选）、灰框（在范围外被忽略）
 *  - 范围限定：取景框（可拖动/四角缩放）或扫描线（可上下拖动）
 */
public class OverlayView extends View {

    /** 不限范围：全都识别 */
    public static final int SCOPE_OFF = 0;
    /** 取景框：只识别框内的条码 */
    public static final int SCOPE_BOX = 1;
    /** 扫描线：只识别压线的条码 */
    public static final int SCOPE_LINE = 2;

    public static class Detection {
        public final String value;
        public final RectF box; // 视图坐标
        public final int format; // ML Kit 的条码格式
        /** 被判定为"将自动记录"的那个（画绿框并标注） */
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

    /** 取景框/扫描线被调整后回调 */
    public interface OnScopeChange {
        void onScopeChanged();
    }

    private static final int DRAG_NONE = 0;
    private static final int DRAG_MOVE = 1;
    private static final int DRAG_TL = 2;
    private static final int DRAG_TR = 3;
    private static final int DRAG_BL = 4;
    private static final int DRAG_BR = 5;
    private static final int DRAG_LINE = 6;

    /** 取景框最小尺寸（归一化） */
    private static final float MIN_ROI_W = 0.16f;
    private static final float MIN_ROI_H = 0.10f;

    private final List<Detection> detections = new ArrayList<>();
    private boolean multi;
    private OnDetectionTap tapListener;
    private OnScopeChange scopeChangeListener;

    private int scopeMode = SCOPE_OFF;
    private final RectF roi = new RectF(0.05f, 0.42f, 0.95f, 0.58f);
    private float lineY = 0.5f;

    private int dragMode = DRAG_NONE;
    private float downX;
    private float downY;
    private final RectF startRoi = new RectF();
    private float startLineY;

    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint();
    private final Paint scopePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scopeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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

        dimPaint.setColor(0x73000000);

        scopePaint.setStyle(Paint.Style.STROKE);
        scopePaint.setStrokeWidth(2.5f * density);

        scopeTextPaint.setTextSize(12f * density);
        scopeTextPaint.setFakeBoldText(true);
    }

    // ------------------------------------------------------------ 范围设置

    public void setScopeMode(int mode) {
        this.scopeMode = mode;
        invalidate();
    }

    public int getScopeMode() {
        return scopeMode;
    }

    public void setRoi(RectF normalized) {
        if (normalized != null) {
            roi.set(normalized);
            invalidate();
        }
    }

    public RectF getRoi() {
        return new RectF(roi);
    }

    public void setLineY(float normalized) {
        this.lineY = Math.max(0.02f, Math.min(0.98f, normalized));
        invalidate();
    }

    public float getLineY() {
        return lineY;
    }

    public void resetScope() {
        roi.set(0.05f, 0.42f, 0.95f, 0.58f);
        lineY = 0.5f;
        invalidate();
    }

    public void setRoiChangeListener(OnScopeChange listener) {
        this.scopeChangeListener = listener;
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

    private RectF roiToView() {
        return new RectF(roi.left * getWidth(), roi.top * getHeight(),
                roi.right * getWidth(), roi.bottom * getHeight());
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    // ------------------------------------------------------------ 绘制

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }

        if (scopeMode != SCOPE_OFF) {
            drawScope(canvas, w, h);
        }

        // 取景框边框
        float inset = 2f * density;
        tmp.set(inset, inset, w - inset, h - inset);
        canvas.drawRect(tmp, borderPaint);

        for (Detection d : detections) {
            if (d == null || d.box == null) {
                continue;
            }
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

            int color = d.auto ? 0xFF00C853 : (multi ? 0xFFFF3B30 : 0xFF00C853);
            boxPaint.setColor(color);
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

    private void drawScope(Canvas canvas, int w, int h) {
        if (scopeMode == SCOPE_BOX) {
            RectF r = roiToView();
            // 框外压暗
            canvas.drawRect(0, 0, w, r.top, dimPaint);
            canvas.drawRect(0, r.bottom, w, h, dimPaint);
            canvas.drawRect(0, r.top, r.left, r.bottom, dimPaint);
            canvas.drawRect(r.right, r.top, w, r.bottom, dimPaint);

            scopePaint.setColor(0xFF00E5FF);
            canvas.drawRect(r, scopePaint);

            // 四角手柄
            Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
            handle.setColor(0xFF00E5FF);
            float hr = 7f * density;
            canvas.drawCircle(r.left, r.top, hr, handle);
            canvas.drawCircle(r.right, r.top, hr, handle);
            canvas.drawCircle(r.left, r.bottom, hr, handle);
            canvas.drawCircle(r.right, r.bottom, hr, handle);

            scopeTextPaint.setColor(0xFF00E5FF);
            float labelX = Math.max(8f * density, r.left);
            String labelText = "取景框 · 拖动移动、拉角缩放";
            float labelW = scopeTextPaint.measureText(labelText);
            if (labelX + labelW > w - 8f * density) {
                labelX = Math.max(8f * density, w - 8f * density - labelW);
            }
            canvas.drawText(labelText, labelX, Math.max(14f * density, r.top - 8f * density),
                    scopeTextPaint);
        } else {
            float y = lineY * h;
            scopePaint.setColor(0xFFFF1744);
            scopePaint.setStrokeWidth(3f * density);
            canvas.drawLine(0, y, w, y, scopePaint);
            scopePaint.setStrokeWidth(2.5f * density);

            Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
            handle.setColor(0xFFFF1744);
            canvas.drawCircle(14f * density, y, 7f * density, handle);
            canvas.drawCircle(w - 14f * density, y, 7f * density, handle);

            scopeTextPaint.setColor(0xFFFF1744);
            canvas.drawText("扫描线 · 上下拖动可调", 30f * density, Math.max(14f * density, y - 10f * density),
                    scopeTextPaint);
        }
    }

    // ------------------------------------------------------------ 触摸

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        if (scopeMode != SCOPE_OFF) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                dragMode = hitTestScope(x, y);
                downX = x;
                downY = y;
                startRoi.set(roi);
                startLineY = lineY;
                if (dragMode != DRAG_NONE) {
                    return true;
                }
            } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE && dragMode != DRAG_NONE) {
                updateScope(x, y);
                invalidate();
                return true;
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                if (dragMode != DRAG_NONE) {
                    boolean moved = Math.abs(x - downX) > 8f * density || Math.abs(y - downY) > 8f * density;
                    int mode = dragMode;
                    dragMode = DRAG_NONE;
                    if (moved) {
                        if (scopeChangeListener != null) {
                            scopeChangeListener.onScopeChanged();
                        }
                        return true;
                    }
                    if (mode != DRAG_LINE) {
                        // 原地轻点：当作点选条码
                        Detection best = findDetection(x, y);
                        if (best != null && tapListener != null) {
                            tapListener.onTap(best);
                        }
                    }
                    return true;
                }
            } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                dragMode = DRAG_NONE;
            }
        }

        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            Detection best = findDetection(x, y);
            if (best != null && tapListener != null) {
                tapListener.onTap(best);
                return true;
            }
        }
        return true;
    }

    private Detection findDetection(float x, float y) {
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
        return best;
    }

    private int hitTestScope(float x, float y) {
        if (scopeMode == SCOPE_LINE) {
            float ly = lineY * getHeight();
            if (Math.abs(y - ly) <= 40f * density) {
                return DRAG_LINE;
            }
            return DRAG_NONE;
        }
        RectF r = roiToView();
        float hr = 32f * density;
        if (Math.abs(x - r.left) <= hr && Math.abs(y - r.top) <= hr) {
            return DRAG_TL;
        }
        if (Math.abs(x - r.right) <= hr && Math.abs(y - r.top) <= hr) {
            return DRAG_TR;
        }
        if (Math.abs(x - r.left) <= hr && Math.abs(y - r.bottom) <= hr) {
            return DRAG_BL;
        }
        if (Math.abs(x - r.right) <= hr && Math.abs(y - r.bottom) <= hr) {
            return DRAG_BR;
        }
        RectF hit = new RectF(r);
        hit.inset(-18f * density, -18f * density);
        if (hit.contains(x, y)) {
            return DRAG_MOVE;
        }
        return DRAG_NONE;
    }

    private void updateScope(float x, float y) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        if (dragMode == DRAG_LINE) {
            lineY = clamp(y / h, 0.02f, 0.98f);
            return;
        }
        float dx = (x - downX) / w;
        float dy = (y - downY) / h;
        RectF r = new RectF(startRoi);
        switch (dragMode) {
            case DRAG_MOVE:
                r.offset(dx, dy);
                if (r.left < 0f) {
                    r.offset(-r.left, 0f);
                }
                if (r.top < 0f) {
                    r.offset(0f, -r.top);
                }
                if (r.right > 1f) {
                    r.offset(1f - r.right, 0f);
                }
                if (r.bottom > 1f) {
                    r.offset(0f, 1f - r.bottom);
                }
                break;
            case DRAG_TL:
                r.left = clamp(startRoi.left + dx, 0f, startRoi.right - MIN_ROI_W);
                r.top = clamp(startRoi.top + dy, 0f, startRoi.bottom - MIN_ROI_H);
                break;
            case DRAG_TR:
                r.right = clamp(startRoi.right + dx, startRoi.left + MIN_ROI_W, 1f);
                r.top = clamp(startRoi.top + dy, 0f, startRoi.bottom - MIN_ROI_H);
                break;
            case DRAG_BL:
                r.left = clamp(startRoi.left + dx, 0f, startRoi.right - MIN_ROI_W);
                r.bottom = clamp(startRoi.bottom + dy, startRoi.top + MIN_ROI_H, 1f);
                break;
            case DRAG_BR:
                r.right = clamp(startRoi.right + dx, startRoi.left + MIN_ROI_W, 1f);
                r.bottom = clamp(startRoi.bottom + dy, startRoi.top + MIN_ROI_H, 1f);
                break;
            default:
                break;
        }
        roi.set(r);
    }
}
