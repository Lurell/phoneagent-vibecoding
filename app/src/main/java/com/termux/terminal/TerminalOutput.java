/*
 * 内联自 termux/termux-app (tag v0.118.0) 的 terminal-emulator / terminal-view 模块。
 * 上游：https://github.com/termux/termux-app
 * 该部分源自 jackpal/Android-Terminal-Emulator，采用 Apache License 2.0。
 * 许可全文见 third_party/termux-terminal/LICENSE-Apache-2.0.txt
 * 本项目的改动见 third_party/termux-terminal/README.md
 */
package com.termux.terminal;

import java.nio.charset.StandardCharsets;

/** A client which receives callbacks from events triggered by feeding input to a {@link TerminalEmulator}. */
public abstract class TerminalOutput {

    /** Write a string using the UTF-8 encoding to the terminal client. */
    public final void write(String data) {
        if (data == null) return;
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        write(bytes, 0, bytes.length);
    }

    /** Write bytes to the terminal client. */
    public abstract void write(byte[] data, int offset, int count);

    /** Notify the terminal client that the terminal title has changed. */
    public abstract void titleChanged(String oldTitle, String newTitle);

    /** Notify the terminal client that text should be copied to clipboard. */
    public abstract void onCopyTextToClipboard(String text);

    /** Notify the terminal client that text should be pasted from clipboard. */
    public abstract void onPasteTextFromClipboard();

    /** Notify the terminal client that a bell character (ASCII 7, bell, BEL, \a, ^G)) has been received. */
    public abstract void onBell();

    public abstract void onColorsChanged();

}
