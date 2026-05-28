package com.tencent.yolov11ncnn.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Neo-Brutalism 点状网格背景
 * 低对比度，仅作视觉引导
 */
public class DottedGridView extends View {

    private Paint dotPaint;
    private static final int GRID_SPACING = 32; // 点间距 32dp
    private static final int DOT_RADIUS = 2;    // 点半径 2dp

    public DottedGridView(Context context) {
        super(context);
        init();
    }

    public DottedGridView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DottedGridView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setColor(0xFFDCDCD0); // 浅灰点
        dotPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();
        float density = getResources().getDisplayMetrics().density;
        float spacing = GRID_SPACING * density;
        float radius = DOT_RADIUS * density;

        // 绘制网格点
        for (float x = 0; x < width; x += spacing) {
            for (float y = 0; y < height; y += spacing) {
                canvas.drawCircle(x, y, radius, dotPaint);
            }
        }
    }
}
