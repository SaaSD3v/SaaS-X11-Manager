package com.termux.x11;

import android.content.Context;
import android.content.Intent;
import android.graphics.Matrix;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.Surface;

import com.termux.x11.input.InputEventSender;
import com.termux.x11.input.InputStub;
import com.termux.x11.input.RenderData;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Host bridge used when LorieView is rendered directly inside SaaS X11 Manager.
 *
 * Upstream Termux:X11 routes its view connection and input pipeline through
 * MainActivity. Embedded mode keeps that responsibility inside this bridge. The
 * Manager may own several X servers at once, while a single visible LorieView is
 * explicitly routed to one selected display.
 */
public final class EmbeddedDisplayHost {
    public static final Handler handler = new Handler(Looper.getMainLooper());
    public static final String EXTRA_X11_DISPLAY =
            "com.saas.x11manager.extra.X11_DISPLAY";

    private static final String DEFAULT_X11_DISPLAY = ":0";
    private static final String ACTION_STOP = "com.termux.x11.ACTION_STOP";
    private static final String ACTION_PREFERENCES_CHANGED =
            "com.termux.x11.ACTION_PREFERENCES_CHANGED";

    private static WeakReference<LorieView> activeView = new WeakReference<>(null);
    private static WeakReference<InputEventSender> inputSender = new WeakReference<>(null);
    private static WeakReference<RenderData> renderData = new WeakReference<>(null);
    private static final Map<String, ICmdEntryInterface> services = new HashMap<>();
    private static volatile String selectedDisplay = DEFAULT_X11_DISPLAY;
    private static Prefs prefs;
    private static int savedMouseButtonState;

    private EmbeddedDisplayHost() {}

    private static String normalizeDisplay(String displayName) {
        if (displayName == null)
            return DEFAULT_X11_DISPLAY;
        String normalized = displayName.trim();
        return normalized.matches(":[0-9]+") ? normalized : DEFAULT_X11_DISPLAY;
    }

    public static synchronized Prefs getPrefs(Context context) {
        if (prefs == null)
            prefs = new Prefs(context.getApplicationContext());
        return prefs;
    }

    public static synchronized void attach(LorieView view) {
        activeView = new WeakReference<>(view);
        inputSender = new WeakReference<>(new InputEventSender(view));
        renderData = new WeakReference<>(new RenderData());
        savedMouseButtonState = 0;
        view.setZOrderOnTop(false);
        view.setZOrderMediaOverlay(false);
        getPrefs(view.getContext());
        reloadInputPreferences(view);
        handler.post(EmbeddedDisplayHost::tryConnect);
    }

    public static synchronized void detach(LorieView view) {
        if (activeView.get() == view) {
            releasePointerCapture(view);
            restoreAndroidPointer(view);
            if (view.connected())
                view.connect(-1);
            activeView.clear();
            inputSender.clear();
            renderData.clear();
            savedMouseButtonState = 0;
        }
    }

    public static synchronized LorieView getActiveView() {
        return activeView.get();
    }

    public static synchronized String getSelectedDisplay() {
        return selectedDisplay;
    }

    /**
     * Route the visible embedded surface to one X11 display. Other server
     * connections remain registered and can be selected later without stopping
     * their containers.
     */
    public static synchronized void selectDisplay(String displayName) {
        String requested = normalizeDisplay(displayName);
        if (requested.equals(selectedDisplay)) {
            handler.post(EmbeddedDisplayHost::tryConnect);
            return;
        }

        LorieView view = activeView.get();
        if (view != null) {
            releasePointerCapture(view);
            restoreAndroidPointer(view);
            if (view.connected())
                view.connect(-1);
        }

        selectedDisplay = requested;
        savedMouseButtonState = 0;
        handler.post(EmbeddedDisplayHost::tryConnect);
    }

    public static boolean isConnected() {
        LorieView view = getActiveView();
        return view != null && view.connected();
    }

    private static synchronized InputEventSender senderFor(LorieView view) {
        InputEventSender sender = inputSender.get();
        if (sender == null || getActiveView() != view) {
            sender = new InputEventSender(view);
            inputSender = new WeakReference<>(sender);
        }
        return sender;
    }

    private static synchronized RenderData renderDataFor(LorieView view) {
        RenderData data = renderData.get();
        if (data == null || getActiveView() != view) {
            data = new RenderData();
            renderData = new WeakReference<>(data);
        }
        return data;
    }

    public static void updateInputTransform(
            LorieView view,
            int screenWidth,
            int screenHeight,
            Matrix inputTransform
    ) {
        RenderData data = renderDataFor(view);
        data.screenWidth = screenWidth;
        data.screenHeight = screenHeight;
        data.setInputTransform(inputTransform);
    }

    private static boolean hasPointerCapture(LorieView view) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && view.hasPointerCapture();
    }

    private static void releasePointerCapture(LorieView view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && view.hasPointerCapture())
            view.releasePointerCapture();
    }

    public static void setCapturingEnabled(boolean enabled) {
        LorieView view = getActiveView();
        if (view == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            return;

        InputEventSender sender = senderFor(view);
        if (enabled && sender.pointerCapture && view.connected())
            view.requestPointerCapture();
        else
            releasePointerCapture(view);
    }

    private static void hideAndroidPointer(LorieView view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            view.setPointerIcon(
                    PointerIcon.getSystemIcon(view.getContext(), PointerIcon.TYPE_NULL)
            );
        }
    }

    private static void restoreAndroidPointer(LorieView view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            view.setPointerIcon(
                    PointerIcon.getSystemIcon(view.getContext(), PointerIcon.TYPE_DEFAULT)
            );
        }
    }

    private static void disconnectActiveView() {
        LorieView view = getActiveView();
        if (view == null)
            return;
        releasePointerCapture(view);
        restoreAndroidPointer(view);
        if (view.connected())
            view.connect(-1);
    }

    private static boolean isMouseEvent(MotionEvent event) {
        int source = event.getSource();
        int actionIndex = Math.max(0, event.getActionIndex());
        int toolType = event.getPointerCount() > 0
                ? event.getToolType(Math.min(actionIndex, event.getPointerCount() - 1))
                : MotionEvent.TOOL_TYPE_UNKNOWN;

        return toolType == MotionEvent.TOOL_TYPE_MOUSE
                || (source & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                || (source & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE;
    }

    private static boolean forwardMouseButtons(
            InputEventSender sender,
            RenderData data,
            MotionEvent event
    ) {
        int current = event.getButtonState();
        boolean handled = false;
        int[][] buttons = {
                {MotionEvent.BUTTON_PRIMARY, InputStub.BUTTON_LEFT},
                {MotionEvent.BUTTON_TERTIARY, InputStub.BUTTON_MIDDLE},
                {MotionEvent.BUTTON_SECONDARY, InputStub.BUTTON_RIGHT}
        };

        for (int[] button : buttons) {
            boolean wasDown = (savedMouseButtonState & button[0]) != 0;
            boolean isDown = (current & button[0]) != 0;
            if (wasDown != isDown) {
                sender.sendMouseEvent(data.getCursorPosition(), button[1], isDown, false);
                handled = true;
            }
        }

        savedMouseButtonState = current;
        return handled;
    }

    private static void updateAbsoluteMousePosition(
            InputEventSender sender,
            RenderData data,
            MotionEvent event
    ) {
        float[] mapped = new float[2];
        data.mapScreenPoint(event.getX(), event.getY(), mapped);
        if (data.setCursorPosition(mapped[0], mapped[1]))
            sender.sendCursorMove(mapped[0], mapped[1], false);
    }

    private static String resolvedCapturedPointerTransform(LorieView view) {
        String transform = getPrefs(view.getContext()).transformCapturedPointer.get();
        if (!"at".equals(transform))
            return transform;

        if (view.getDisplay() == null)
            return "no";

        switch (view.getDisplay().getRotation()) {
            case Surface.ROTATION_90:
                return "cc";
            case Surface.ROTATION_180:
                return "ud";
            case Surface.ROTATION_270:
                return "c";
            case Surface.ROTATION_0:
            default:
                return "no";
        }
    }

    private static float[] transformRelativePointer(LorieView view, float x, float y) {
        String transform = resolvedCapturedPointerTransform(view);
        float originalX = x;
        switch (transform) {
            case "c":
                x = -y;
                y = originalX;
                break;
            case "cc":
                x = y;
                y = -originalX;
                break;
            case "ud":
                x = -x;
                y = -y;
                break;
            default:
                break;
        }
        return new float[]{x, y};
    }

    private static void updateCapturedMousePosition(
            LorieView view,
            InputEventSender sender,
            MotionEvent event
    ) {
        InputDevice device = event.getDevice();
        boolean axisRelative = device != null
                && device.getMotionRange(MotionEvent.AXIS_RELATIVE_X) != null;
        boolean sourceRelative =
                (event.getSource() & InputDevice.SOURCE_MOUSE_RELATIVE)
                        == InputDevice.SOURCE_MOUSE_RELATIVE;

        if (!axisRelative && !sourceRelative)
            return;

        float x = axisRelative
                ? event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
                : event.getX();
        float y = axisRelative
                ? event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
                : event.getY();
        float[] transformed = transformRelativePointer(view, x, y);
        float density = view.getResources().getDisplayMetrics().density;
        float scale = sender.capturedPointerSpeedFactor * density;
        sender.sendCursorMove(transformed[0] * scale, transformed[1] * scale, true);
    }

    private static boolean handleMouseMotion(
            LorieView view,
            InputEventSender sender,
            RenderData data,
            MotionEvent event
    ) {
        if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            float scrollY = -100f * event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            float scrollX = -100f * event.getAxisValue(MotionEvent.AXIS_HSCROLL);
            sender.sendMouseWheelEvent(scrollX, scrollY);
            return true;
        }

        boolean buttonsHandled = forwardMouseButtons(sender, data, event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_BUTTON_PRESS:
            case MotionEvent.ACTION_BUTTON_RELEASE:
                return true;

            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_MOVE:
                if (hasPointerCapture(view))
                    updateCapturedMousePosition(view, sender, event);
                else
                    updateAbsoluteMousePosition(sender, data, event);
                return true;

            case MotionEvent.ACTION_DOWN:
                if (!hasPointerCapture(view))
                    updateAbsoluteMousePosition(sender, data, event);
                return true;

            case MotionEvent.ACTION_UP:
                if (!hasPointerCapture(view))
                    updateAbsoluteMousePosition(sender, data, event);
                setCapturingEnabled(true);
                return true;

            default:
                return buttonsHandled;
        }
    }

    public static boolean handleMotion(LorieView view, MotionEvent event) {
        if (!view.connected())
            return false;

        view.requestFocus();
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN)
            view.requestUnbufferedDispatch(event);
        hideAndroidPointer(view);

        InputEventSender sender = senderFor(view);
        RenderData data = renderDataFor(view);
        if (event.getDeviceId() >= 0) {
            sender.releaseStuckModifiers(event.getMetaState());
            sender.syncLockKeysState(event.getMetaState());
        }

        if (isMouseEvent(event))
            return handleMouseMotion(view, sender, data, event);

        sender.sendTouchEvent(event, data);
        return true;
    }

    public static boolean handleKey(LorieView view, KeyEvent event) {
        if (!view.connected())
            return false;

        Prefs p = getPrefs(view.getContext());
        if (p.filterOutWinkey.get()
                && (event.getKeyCode() == KeyEvent.KEYCODE_META_LEFT
                || event.getKeyCode() == KeyEvent.KEYCODE_META_RIGHT
                || event.isMetaPressed()))
            return false;

        if (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE
                && event.getAction() == KeyEvent.ACTION_UP
                && event.hasNoModifiers())
            setCapturingEnabled(false);

        InputEventSender sender = senderFor(view);
        return sender.sendKeyEvent(event);
    }

    public static boolean sendKeyCode(int keyCode, boolean down) {
        LorieView view = getActiveView();
        if (view == null || !view.connected())
            return false;
        view.sendKeyEvent(0, keyCode, down);
        return true;
    }

    public static boolean tapKeyCode(int keyCode) {
        return sendKeyCode(keyCode, true) && sendKeyCode(keyCode, false);
    }

    public static boolean sendText(String text) {
        LorieView view = getActiveView();
        if (view == null || !view.connected() || text == null)
            return false;
        view.sendTextEvent(text.getBytes(StandardCharsets.UTF_8));
        return true;
    }

    private static boolean isExternalInputDevice(InputDevice device) {
        if (device == null || device.isVirtual())
            return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            return device.isExternal();
        try {
            Object result = InputDevice.class
                    .getDeclaredMethod("isExternal")
                    .invoke(device);
            if (result instanceof Boolean)
                return (Boolean) result;
        } catch (Exception ignored) {
        }
        return true;
    }

    private static boolean hasExternalKeyboard() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device != null
                    && device.supportsSource(InputDevice.SOURCE_KEYBOARD)
                    && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC
                    && isExternalInputDevice(device))
                return true;
        }
        return false;
    }

    public static boolean toggleSoftKeyboard() {
        LorieView view = getActiveView();
        if (view == null || !view.connected())
            return false;

        Prefs p = getPrefs(view.getContext());
        if (hasExternalKeyboard() && !p.showIMEWhileExternalConnected.get())
            view.setKeyboardVisible(false);
        else
            view.toggleKeyboardVisible();
        return true;
    }

    public static boolean adjustRendererZoom(int delta) {
        LorieView view = getActiveView();
        if (view == null || !view.connected())
            return false;
        view.adjustRendererZoom(delta);
        return true;
    }

    public static boolean resetRendererZoom() {
        LorieView view = getActiveView();
        if (view == null || !view.connected())
            return false;
        view.resetRendererZoom();
        return true;
    }

    private static void reloadInputPreferences(LorieView view) {
        InputEventSender sender = senderFor(view);
        Prefs p = getPrefs(view.getContext());
        sender.tapToMove = p.tapToMove.get();
        sender.preferScancodes = p.preferScancodes.get();
        sender.pointerCapture = p.pointerCapture.get();
        sender.scaleTouchpad = p.scaleTouchpad.get()
                && "1".equals(p.touchMode.get())
                && !"native".equals(p.displayResolutionMode.get());
        sender.capturedPointerSpeedFactor =
                ((float) p.capturedPointerSpeedFactor.get()) / 100f;
        sender.dexMetaKeyCapture = p.dexMetaKeyCapture.get();
        sender.pauseKeyInterceptingWithEsc = p.pauseKeyInterceptingWithEsc.get();
        sender.stylusIsMouse = p.stylusIsMouse.get();
        sender.stylusButtonContactModifierMode = p.stylusButtonContactModifierMode.get();

        if (!sender.pointerCapture)
            releasePointerCapture(view);
    }

    /**
     * LorieView.reloadPreferences() normally delegates device refresh to
     * TouchInputHandler, whose upstream implementation assumes MainActivity.
     * Embedded mode performs the renderer-level action needed here itself.
     */
    public static void refreshInputDevices(LorieView view) {
        boolean stylusAvailable = false;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device != null && device.supportsSource(InputDevice.SOURCE_STYLUS)) {
                stylusAvailable = true;
                break;
            }
        }
        view.requestStylusEnabled(stylusAvailable);

        Prefs p = getPrefs(view.getContext());
        if (hasExternalKeyboard() && !p.showIMEWhileExternalConnected.get())
            view.setKeyboardVisible(false);
    }

    public static void onBroadcastReceive(Context context, Intent intent) {
        if (intent == null)
            return;

        String action = intent.getAction();
        if (CmdEntryPoint.ACTION_START.equals(action)) {
            Bundle bundle = intent.getBundleExtra(null);
            IBinder binder = bundle == null ? null : bundle.getBinder(null);
            if (binder != null) {
                String displayName = intent.getStringExtra(EXTRA_X11_DISPLAY);
                setService(displayName, ICmdEntryInterface.Stub.asInterface(binder));
            }
        } else if (ACTION_STOP.equals(action)) {
            synchronized (EmbeddedDisplayHost.class) {
                services.clear();
            }
            disconnectActiveView();
        } else if (ACTION_PREFERENCES_CHANGED.equals(action)) {
            LorieView view = getActiveView();
            if (view != null) {
                view.reloadPreferences(getPrefs(context));
                reloadInputPreferences(view);
                view.triggerCallback();
            }
        }
    }

    private static synchronized void setService(
            String displayName,
            ICmdEntryInterface candidate
    ) {
        if (candidate == null)
            return;

        String key = normalizeDisplay(displayName);
        ICmdEntryInterface existing = services.get(key);
        if (existing != null && existing.asBinder() == candidate.asBinder()) {
            if (key.equals(selectedDisplay))
                handler.post(EmbeddedDisplayHost::tryConnect);
            return;
        }

        services.put(key, candidate);
        IBinder binder = candidate.asBinder();
        try {
            binder.linkToDeath(() -> {
                boolean selected;
                synchronized (EmbeddedDisplayHost.class) {
                    ICmdEntryInterface current = services.get(key);
                    if (current != null && current.asBinder() == binder)
                        services.remove(key);
                    selected = key.equals(selectedDisplay);
                }
                if (selected)
                    handler.post(EmbeddedDisplayHost::disconnectActiveView);
            }, 0);
        } catch (RemoteException ignored) {
            ICmdEntryInterface current = services.get(key);
            if (current != null && current.asBinder() == binder)
                services.remove(key);
        }

        if (key.equals(selectedDisplay))
            handler.post(EmbeddedDisplayHost::tryConnect);
    }

    public static void tryConnect() {
        LorieView view = getActiveView();
        if (view == null || view.connected())
            return;

        final String displayName;
        final ICmdEntryInterface current;
        synchronized (EmbeddedDisplayHost.class) {
            displayName = selectedDisplay;
            current = services.get(displayName);
        }

        if (current == null) {
            view.requestConnection();
            handler.postDelayed(EmbeddedDisplayHost::tryConnect, 250);
            return;
        }

        try {
            ParcelFileDescriptor fd = current.getXConnection();
            if (fd != null) {
                Log.i(
                        "EmbeddedDisplayHost",
                        "Connecting embedded LorieView to X server " + displayName
                );
                view.connect(fd.detachFd());
                view.reloadPreferences(getPrefs(view.getContext()));
                reloadInputPreferences(view);
                hideAndroidPointer(view);
                view.requestFocus();
                view.triggerCallback();
                return;
            }
        } catch (Exception e) {
            Log.w(
                    "EmbeddedDisplayHost",
                    "Embedded X11 connection failed for " + displayName + "; retrying",
                    e
            );
            synchronized (EmbeddedDisplayHost.class) {
                ICmdEntryInterface registered = services.get(displayName);
                if (registered != null && registered.asBinder() == current.asBinder())
                    services.remove(displayName);
            }
        }

        handler.postDelayed(EmbeddedDisplayHost::tryConnect, 250);
    }
}
