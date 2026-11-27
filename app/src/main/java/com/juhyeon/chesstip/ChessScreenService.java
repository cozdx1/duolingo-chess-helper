package com.juhyeon.chesstip;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

/** Verifies the focused match window before the capture service reads a board. */
public final class ChessScreenService extends AccessibilityService {
    private static ChessScreenService instance;
    private static Runnable windowListener;
    private ChessScreens.ActivityWindow activity;

    record Screen(ChessScreens.Foreground kind, ChessScreens.ActivityWindow activity) {}

    static ChessScreenService current() { return instance; }
    static void setWindowListener(Runnable listener) { windowListener = listener; }

    private static void notifyWindowListener() {
        if (windowListener != null) windowListener.run();
    }

    static boolean enabled(Context context) {
        String setting = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (setting == null) return false;
        ComponentName expected = new ComponentName(context, ChessScreenService.class);
        for (String name : setting.split(":")) {
            if (expected.equals(ComponentName.unflattenFromString(name))) return true;
        }
        return false;
    }

    @Override protected void onServiceConnected() {
        activity = null;
        instance = this;
        Log.i("ChessTip", "SCREEN_SERVICE_CONNECTED");
        notifyWindowListener();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            ChessScreens.ActivityWindow seen = ChessScreens.activityWindow(event.getPackageName(), event.getClassName(), event.getWindowId());
            if (seen == null || seen.equals(activity)) return;
            activity = seen;
            Log.i("ChessTip", "DUO_WINDOW " + seen.name() + " id=" + seen.id());
        } else if (event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            int changes = event.getWindowChanges();
            if (activity != null && activity.id() == event.getWindowId()
                    && (changes & AccessibilityEvent.WINDOWS_CHANGE_REMOVED) != 0) activity = null;
            int relevant = AccessibilityEvent.WINDOWS_CHANGE_ADDED | AccessibilityEvent.WINDOWS_CHANGE_REMOVED
                    | AccessibilityEvent.WINDOWS_CHANGE_FOCUSED | AccessibilityEvent.WINDOWS_CHANGE_PIP;
            if ((changes & relevant) == 0) return;
        } else return;
        notifyWindowListener();
    }

    Screen screen() {
        if (!getSystemService(PowerManager.class).isInteractive() || getSystemService(KeyguardManager.class).isKeyguardLocked()) {
            return new Screen(ChessScreens.Foreground.OTHER, null);
        }
        for (AccessibilityWindowInfo window : getWindows()) {
            if (window.getDisplayId() != Display.DEFAULT_DISPLAY || !window.isFocused()) continue;
            if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || window.isInPictureInPictureMode()) {
                return new Screen(ChessScreens.Foreground.OTHER, null);
            }
            AccessibilityNodeInfo root = window.getRoot(0);
            if (root == null || root.getWindowId() != window.getId()) break;
            AccessibilityWindowInfo parent = activity != null && activity.id() != window.getId() ? window.getParent() : null;
            ChessScreens.Foreground kind = ChessScreens.foreground(activity, root.getPackageName(), window.getId(),
                    parent == null ? -1 : parent.getId());
            return new Screen(kind, activity);
        }
        return new Screen(ChessScreens.Foreground.UNKNOWN, null);
    }

    @Override public void onInterrupt() { Log.i("ChessTip", "SCREEN_SERVICE_INTERRUPTED"); }

    @Override public void onDestroy() {
        if (instance == this) {
            instance = null;
            notifyWindowListener();
        }
        super.onDestroy();
    }
}
