package com.termux.x11;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;
import android.view.KeyEvent;

import com.termux.x11.input.InputEventSender;

import java.lang.ref.WeakReference;

/**
 * Host bridge used when LorieView is rendered directly inside SaaS X11 Manager.
 *
 * Upstream Termux:X11 routes its view connection through MainActivity. The
 * Manager has no separate display Activity, so this bridge owns the active
 * SurfaceView, preferences and CmdEntryPoint binder connection instead.
 */
public final class EmbeddedDisplayHost {
    public static final Handler handler = new Handler(Looper.getMainLooper());

    private static final String ACTION_STOP = "com.termux.x11.ACTION_STOP";
    private static final String ACTION_PREFERENCES_CHANGED = "com.termux.x11.ACTION_PREFERENCES_CHANGED";

    private static WeakReference<LorieView> activeView = new WeakReference<>(null);
    private static WeakReference<InputEventSender> inputSender = new WeakReference<>(null);
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
        getPrefs(view.getContext());
        handler.post(EmbeddedDisplayHost::tryConnect);
    }

    public static synchronized void detach(LorieView view) {
        if (activeView.get() == view) {
            activeView.clear();
            inputSender.clear();
        }
    }

    public static synchronized LorieView getActiveView() {
        return activeView.get();
    }

    public static boolean handleKey(LorieView view, KeyEvent event) {
        InputEventSender sender = inputSender.get();
        if (sender == null || getActiveView() != view) {
            sender = new InputEventSender(view);
            inputSender = new WeakReference<>(sender);
        }
        return sender.sendKeyEvent(event);
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
