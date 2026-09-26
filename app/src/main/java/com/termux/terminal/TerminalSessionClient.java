/*
 * 内联自 termux/termux-app (tag v0.118.0) 的 terminal-emulator / terminal-view 模块。
 * 上游：https://github.com/termux/termux-app
 * 该部分源自 jackpal/Android-Terminal-Emulator，采用 Apache License 2.0。
 * 许可全文见 third_party/termux-terminal/LICENSE-Apache-2.0.txt
 * 本项目的改动见 third_party/termux-terminal/README.md
 */
package com.termux.terminal;

/**
 * The interface for communication between {@link TerminalSession} and its client. It is used to
 * send callbacks to the client when {@link TerminalSession} changes or for sending other
 * back data to the client like logs.
 */
public interface TerminalSessionClient {

    void onTextChanged(TerminalSession changedSession);

    void onTitleChanged(TerminalSession changedSession);

    void onSessionFinished(TerminalSession finishedSession);

    void onCopyTextToClipboard(TerminalSession session, String text);

    void onPasteTextFromClipboard(TerminalSession session);

    void onBell(TerminalSession session);

    void onColorsChanged(TerminalSession session);

    void onTerminalCursorStateChange(boolean state);



    Integer getTerminalCursorStyle();



    void logError(String tag, String message);

    void logWarn(String tag, String message);

    void logInfo(String tag, String message);

    void logDebug(String tag, String message);

    void logVerbose(String tag, String message);

    void logStackTraceWithMessage(String tag, String message, Exception e);

    void logStackTrace(String tag, Exception e);

}
