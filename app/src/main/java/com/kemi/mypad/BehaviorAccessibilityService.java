package com.kemi.mypad;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

/** Records semantic UI actions without recording entered text or screen contents. */
public final class BehaviorAccessibilityService extends AccessibilityService {
    private static volatile boolean connected;

    static boolean isConnected() { return connected; }

    @Override protected void onServiceConnected() {
        connected = true;
        BehaviorRecordStore.append(this, "accessibility_connected", new JSONObject());
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !BehaviorRecordStore.isEnabled(this)) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED
                && type != AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
                && type != AccessibilityEvent.TYPE_VIEW_SCROLLED
                && type != AccessibilityEvent.TYPE_VIEW_SELECTED
                && type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;
        try {
            JSONObject detail = new JSONObject();
            detail.put("event", AccessibilityEvent.eventTypeToString(type));
            detail.put("package", string(event.getPackageName()));
            detail.put("class", string(event.getClassName()));
            detail.put("windowId", event.getWindowId());
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                Rect bounds = new Rect();
                source.getBoundsInScreen(bounds);
                detail.put("viewId", string(source.getViewIdResourceName()));
                detail.put("nodeClass", string(source.getClassName()));
                detail.put("bounds", bounds.left + "," + bounds.top + "," + bounds.right + "," + bounds.bottom);
                detail.put("clickable", source.isClickable());
                source.recycle();
            }
            if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                detail.put("scrollX", event.getScrollX());
                detail.put("scrollY", event.getScrollY());
                detail.put("fromIndex", event.getFromIndex());
                detail.put("toIndex", event.getToIndex());
            }
            BehaviorRecordStore.append(this, "ui_action", detail);
        } catch (Exception ignored) { }
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (event == null || event.getAction() != KeyEvent.ACTION_UP || !BehaviorRecordStore.isEnabled(this)) return false;
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_HOME || code == KeyEvent.KEYCODE_APP_SWITCH
                || code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            JSONObject detail = BehaviorRecordStore.json("keyCode", code);
            BehaviorRecordStore.append(this, "navigation_key", detail);
        }
        return false;
    }

    @Override public void onInterrupt() { }

    @Override public boolean onUnbind(android.content.Intent intent) {
        connected = false;
        BehaviorRecordStore.append(this, "accessibility_disconnected", new JSONObject());
        return super.onUnbind(intent);
    }

    private static String string(CharSequence value) { return value == null ? "" : value.toString(); }
}
