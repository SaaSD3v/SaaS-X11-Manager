package com.termux.x11;

import android.content.Context;
import android.content.Intent;
import android.graphics.Matrix;
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

import com.termux.x11.input.InputEventSender;
import com.termux.x11.input.RenderData;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;

/**
 * Host bridge used when LorieView is rendered directly inside SaaS X11 Manager.
 *
 * Upstream Termux:X11 routes its view connection through MainActivity. The
 * Manager has no separate display Activity, so this bridge owns the active
 * SurfaceView, preferences, input state and CmdEntryPoint binder connection.
 */
public final class EmbeddedDisplayHost {
    public static final Handler handler = new Handler(Looper.getMainLooper());

    private static final String ACTION_STOP = "com.termux.x11.ACTION_STOP";
    private static final String ACTION_PREFERENCES_CHANGED = "com.termux.x11.ACTION_PREFERENCES_CHANGED";

    private static WeakReference<LorieView> activeView = new WeakReference<>(null);
    private static WeakReference<InputEventSender> inputSender = new WeakReference<>(null);
    private static WeakReference<RenderData> renderData = new WeakReference<>(null);
    private static volatile ICmdEntryInterface service;
    private static Prefs prefs;

    private EmbeddedDisplayHost() {}

    public static synchronized Prefs getPrefs(Context context) {
        if (prefs == null)
            prefs = new Prefs(context.getApplicationContext());
        return prefs;
    }

    public static synchronized void attach(LorieView view) {
        activeView = new WeakReference<>(view);
        inputSender = new WeakReference<>(new InputEventSender(view));
        renderData = new WeakReference<>(new RenderData());
        view.setZOrderOnTop(false);
        view.setZOrderMediaOverlay(false);
        getPrefs(view.getContext());
        reloadInputPreferences(view);
        handler.post(EmbeddedDisplayHost::tryConnect);
    }

    public static synchronized void detach(LorieView view) {
        if (activeView.get() == view) {
            if (view.connected())
                view.connect(-1);
            activeView.clear();
            inputSender.clear();
            renderData.clear();
        }
    }

    public static synchronized LorieView getActiveView() {
        return activeView.get();
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

    public static boolean handleTouch(LorieView view, MotionEvent event) {
        if (!view.connected())
            return false;

        view.requestFocus();
        InputEventSender sender = senderFor(view);
        RenderData data = renderDataFor(view);
        sender.releaseStuckModifiers(event.getMetaState());
        sender.syncLockKeysState(event.getMetaState());
        sender.sendTouchEvent(event, data);
        return true;
    }

    public static boolean handleKey(LorieView view, KeyEvent event) {
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

    public static boolean toggleSoftKeyboard() {
        LorieView view = getActiveView();
        if (view == null || !view.connected())
            return false;
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
        sender.capturedPointerSpeedFactor = ((float) p.capturedPointerSpeedFactor.get()) / 100f;
        sender.dexMetaKeyCapture = p.dexMetaKeyCapture.get();
        sender.pauseKeyInterceptingWithEsc = p.pauseKeyInterceptingWithEsc.get();
        sender.stylusIsMouse = p.stylusIsMouse.get();
        sender.stylusButtonContactModifierMode = p.stylusButtonContactModifierMode.get();
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
    }

    public static void onBroadcastReceive(Context context, Intent intent) {
        if (intent == null)
            return;

        String action = intent.getAction();
        if (CmdEntryPoint.ACTION_START.equals(action)) {
            Bundle bundle = intent.getBundleExtra(null);
            IBinder binder = bundle == null ? null : bundle.getBinder(null);
            if (binder != null)
                setService(ICmdEntryInterface.Stub.asInterface(binder));
        } else if (ACTION_STOP.equals(action)) {
            service = null;
            LorieView view = getActiveView();
            if (view != null && view.connected())
                view.connect(-1);
        } else if (ACTION_PREFERENCES_CHANGED.equals(action)) {
            LorieView view = getActiveView();
            if (view != null) {
                view.reloadPreferences(getPrefs(context));
                reloadInputPreferences(view);
                view.triggerCallback();
            }
        }
    }

    private static synchronized void setService(ICmdEntryInterface candidate) {
        if (candidate == null)
            return;
        if (service != null && service.asBinder() == candidate.asBinder()) {
            handler.post(EmbeddedDisplayHost::tryConnect);
            return;
        }

        service = candidate;
        try {
            candidate.asBinder().linkToDeath(() -> {
                service = null;
                handler.post(() -> {
                    LorieView view = getActiveView();
                    if (view != null && view.connected())
                        view.connect(-1);
                });
            }, 0);
        } catch (RemoteException ignored) {
            service = null;
        }
        handler.post(EmbeddedDisplayHost::tryConnect);
    }

    public static void tryConnect() {
        LorieView view = getActiveView();
        if (view == null || view.connected())
            return;

        ICmdEntryInterface current = service;
        if (current == null) {
            view.requestConnection();
            handler.postDelayed(EmbeddedDisplayHost::tryConnect, 250);
            return;
        }

        try {
            ParcelFileDescriptor fd = current.getXConnection();
            if (fd != null) {
                Log.i("EmbeddedDisplayHost", "Connecting embedded LorieView to X server");
                view.connect(fd.detachFd());
                view.reloadPreferences(getPrefs(view.getContext()));
                reloadInputPreferences(view);
                view.triggerCallback();
                return;
            }
        } catch (Exception e) {
            Log.w("EmbeddedDisplayHost", "Embedded X11 connection failed; retrying", e);
            service = null;
        }

        handler.postDelayed(EmbeddedDisplayHost::tryConnect, 250);
    }
}
