package com.miui.newhome.view.gestureview;

import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.FrameLayout;

@SuppressWarnings("unused")
public class NewHomeView extends FrameLayout {
    // Intentionally package-private so HomeFeedContainer's reflective
    // getConstructor(Context, AttributeSet) cleanly skips creating an empty feed view.
    NewHomeView(Context context) {
        super(context);
    }

    public static NewHomeState getNewHomeState() {
        return NewHomeState.HIDE;
    }

    public void changeState(NewHomeState state) {
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return super.dispatchKeyEvent(event);
    }

    public boolean isAppBarLayoutScroll() {
        return false;
    }

    public boolean isViewPagerScorll() {
        return false;
    }

    public boolean onBackPressed() {
        return false;
    }

    public void onDestory() {
    }

    public void onNewIntent(Intent intent) {
    }

    public void onPause() {
    }

    public void onResume() {
    }

    public void onTransProgress(float progress) {
    }

    public void setFeedActionListener(FeedActionListener listener) {
    }

    public boolean shouldContainerScroll(MotionEvent ev) {
        return false;
    }
}
