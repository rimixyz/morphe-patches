package org.libpag;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.widget.ImageView;

@SuppressWarnings("unused")
public class PAGImageView extends ImageView {
    public interface PAGImageViewListener {
        void onAnimationStart(PAGImageView view);
        void onAnimationEnd(PAGImageView view);
        void onAnimationCancel(PAGImageView view);
        void onAnimationRepeat(PAGImageView view);
        void onAnimationUpdate(PAGImageView view);
    }

    public PAGImageView(Context context) {
        super(context);
    }

    public PAGImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public PAGImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
    }

    public void addListener(PAGImageViewListener listener) {
    }

    public Bitmap currentImage() {
        return null;
    }

    public int numFrames() {
        return 0;
    }

    public void pause() {
    }

    public void play() {
    }

    public void setComposition(PAGComposition composition) {
    }

    public void setCurrentFrame(int frame) {
    }

    public void setPathAsync(String path, PAGFile.LoadListener listener) {
    }

    public void setRepeatCount(int repeatCount) {
    }

    public void setScaleMode(int mode) {
    }
}
