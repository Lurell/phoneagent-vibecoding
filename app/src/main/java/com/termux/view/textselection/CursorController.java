/*
 * 内联自 termux/termux-app (tag v0.118.0) 的 terminal-emulator / terminal-view 模块。
 * 上游：https://github.com/termux/termux-app
 * 该部分源自 jackpal/Android-Terminal-Emulator，采用 Apache License 2.0。
 * 许可全文见 third_party/termux-terminal/LICENSE-Apache-2.0.txt
 * 本项目的改动见 third_party/termux-terminal/README.md
 */
package com.termux.view.textselection;

import android.view.MotionEvent;
import android.view.ViewTreeObserver;

import com.termux.view.TerminalView;

/**
 * A CursorController instance can be used to control cursors in the text.
 * It is not used outside of {@link TerminalView}.
 */
public interface CursorController extends ViewTreeObserver.OnTouchModeChangeListener {
    /**
     * Show the cursors on screen. Will be drawn by {@link #render()} by a call during onDraw.
     * See also {@link #hide()}.
     */
    void show(MotionEvent event);

    /**
     * Hide the cursors from screen.
     * See also {@link #show(MotionEvent event)}.
     */
    boolean hide();

    /**
     * Render the cursors.
     */
    void render();

    /**
     * Update the cursor positions.
     */
    void updatePosition(TextSelectionHandleView handle, int x, int y);

    /**
     * This method is called by {@link #onTouchEvent(MotionEvent)} and gives the cursors
     * a chance to become active and/or visible.
     *
     * @param event The touch event
     */
    boolean onTouchEvent(MotionEvent event);

    /**
     * Called when the view is detached from window. Perform house keeping task, such as
     * stopping Runnable thread that would otherwise keep a reference on the context, thus
     * preventing the activity to be recycled.
     */
    void onDetached();

    /**
     * @return true if the cursors are currently active.
     */
    boolean isActive();

}
