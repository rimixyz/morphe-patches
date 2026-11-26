package org.libpag;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

@SuppressWarnings("unused")
public class PAGView extends View {
    public interface PAGViewListener {
        void onAnimationStart(PAGView view);
        void onAnimationEnd(PAGView view);
        void onAnimationCancel(PAGView view);
        void onAnimationRepeat(PAGView view);
        void onAnimationUpdate(PAGView view);
    }

    public PAGView(Context context) {
        super(context);
    }

    public void addListener(PAGViewListener listener) {
    }

    public void freeCache() {
    }

    public double getProgress() {
        return 0.0d;
    }

    public void pause() {
    }

    public void play() {
    }

    @Override
    public void setBackground(Drawable background) {
        super.setBackground(background);
    }

    public void setComposition(PAGComposition composition) {
    }

    @Override
    public void setLayoutParams(ViewGroup.LayoutParams params) {
        super.setLayoutParams(params);
    }

    public void setPathAsync(String path, PAGFile.LoadListener listener) {
    }

    public void setProgress(double value) {
    }

    public void setRepeatCount(int repeatCount) {
    }

    public void setScaleMode(int mode) {
    }

    public void stop() {
    }
}
