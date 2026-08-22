package com.termux.x11;

import android.content.SharedPreferences;
import android.graphics.Matrix;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import com.termux.x11.input.InputEventSender;
import com.termux.x11.input.RenderData;

/**
 * Stylus-only input bridge for the embedded LorieView.
 *
 * Upstream Termux:X11 handles stylus events separately from touchscreen and
 * mouse events so pressure, tilt, orientation, eraser state and side buttons
 * reach XInput. Embedded mode keeps the same semantics without instantiating
 * the standalone MainActivity/TouchInputHandler graph.
 */
public final class EmbeddedStylusInputController {
    private static final int DEFAULT_CONTACT_BUTTON = 1;

    private final LorieView view;
    private final InputEventSender sender;
    private final RenderData renderData = new RenderData();
    private final float[] mappedPoint = new float[2];
    private final SharedPreferences preferenceStore;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;
    private final View.OnAttachStateChangeListener attachStateListener;

    private boolean preferenceListenerRegistered;

    public EmbeddedStylusInputController(LorieView view) {
        if (view == null)
            throw new NullPointerException("view");
        this.view = view;
        this.sender = new InputEventSender(view);
        this.preferenceStore = EmbeddedDisplayHost.getPrefs(view.getContext()).get();
        this.preferenceListener = (store, key) -> refreshPreferences();
        this.attachStateListener = new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View attachedView) {
                registerPreferenceListener();
                refreshPreferences();
            }

            @Override
            public void onViewDetachedFromWindow(View detachedView) {
                unregisterPreferenceListener();
            }
        };
        view.addOnAttachStateChangeListener(attachStateListener);
        registerPreferenceListener();
        refreshPreferences();
    }

    public boolean handles(MotionEvent event) {
        if (event == null || event.getPointerCount() == 0)
            return false;
        int index = Math.max(0, Math.min(event.getActionIndex(), event.getPointerCount() - 1));
        int toolType = event.getToolType(index);
        return toolType == MotionEvent.TOOL_TYPE_STYLUS
                || toolType == MotionEvent.TOOL_TYPE_ERASER;
    }

    public void updateInputTransform(int screenWidth, int screenHeight, Matrix inputTransform) {
        if (inputTransform == null)
            return;
        renderData.screenWidth = screenWidth;
        renderData.screenHeight = screenHeight;
        renderData.setInputTransform(inputTransform);
    }

    public boolean handle(MotionEvent event) {
        if (event == null || !view.connected() || !handles(event))
            return false;

        view.requestFocus();
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN)
            view.requestUnbufferedDispatch(event);

        if (event.getDeviceId() >= 0) {
            sender.releaseStuckModifiers(event.getMetaState());
            sender.syncLockKeysState(event.getMetaState());
        }

        int index = Math.max(0, Math.min(event.getActionIndex(), event.getPointerCount() - 1));
        float screenX = event.getX(index);
        float screenY = event.getY(index);
        renderData.mapScreenPoint(screenX, screenY, mappedPoint);

        float x = clamp(mappedPoint[0], 0f, renderData.screenWidth);
        float y = clamp(mappedPoint[1], 0f, renderData.screenHeight);
        float orientation = axisAvailable(event, MotionEvent.AXIS_ORIENTATION)
                ? event.getAxisValue(MotionEvent.AXIS_ORIENTATION, index)
                : 0f;
        float tilt = axisAvailable(event, MotionEvent.AXIS_TILT)
                ? event.getAxisValue(MotionEvent.AXIS_TILT, index)
                : 0f;

        int tiltX = (int) Math.round(
                Math.asin(-Math.sin(orientation) * Math.sin(tilt)) * 63.5 - 0.5
        );
        int tiltY = (int) Math.round(
                Math.asin(Math.cos(orientation) * Math.sin(tilt)) * 63.5 - 0.5
        );
        int pressure = Math.round(clamp(event.getPressure(index), 0f, 1f) * 65535f);
        int buttons = extractButtons(event, index);
        boolean eraser = event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER;

        sender.sendStylusEvent(
                x,
                y,
                pressure,
                tiltX,
                tiltY,
                convertOrientation(orientation),
                buttons,
                eraser,
                sender.stylusIsMouse
        );

        if (event.getActionMasked() == MotionEvent.ACTION_UP)
            EmbeddedDisplayHost.setCapturingEnabled(true);
        return true;
    }

    public void dispose() {
        unregisterPreferenceListener();
        view.removeOnAttachStateChangeListener(attachStateListener);
    }

    private void registerPreferenceListener() {
        if (preferenceListenerRegistered)
            return;
        preferenceStore.registerOnSharedPreferenceChangeListener(preferenceListener);
        preferenceListenerRegistered = true;
    }

    private void unregisterPreferenceListener() {
        if (!preferenceListenerRegistered)
            return;
        preferenceStore.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        preferenceListenerRegistered = false;
    }

    private void refreshPreferences() {
        Prefs prefs = EmbeddedDisplayHost.getPrefs(view.getContext());
        sender.stylusIsMouse = prefs.stylusIsMouse.get();
        sender.stylusButtonContactModifierMode =
                prefs.stylusButtonContactModifierMode.get();
    }

    private boolean axisAvailable(MotionEvent event, int axis) {
        InputDevice device = event.getDevice();
        return device != null && device.getMotionRange(axis) != null;
    }

    private int extractButtons(MotionEvent event, int index) {
        boolean contact = event.getPressure(index) > 0f;
        boolean primary = hasButton(event, MotionEvent.BUTTON_STYLUS_PRIMARY);
        boolean secondary = hasButton(event, MotionEvent.BUTTON_STYLUS_SECONDARY);

        if (sender.stylusButtonContactModifierMode) {
            if (!contact)
                return 0;
            if (secondary)
                return 1 << 1;
            if (primary)
                return 1 << 2;
            return DEFAULT_CONTACT_BUTTON;
        }

        int buttons = contact ? DEFAULT_CONTACT_BUTTON : 0;
        if (secondary)
            buttons |= 1 << 1;
        if (primary)
            buttons |= 1 << 2;
        return buttons;
    }

    private boolean hasButton(MotionEvent event, int button) {
        return (event.getButtonState() & button) == button;
    }

    private int convertOrientation(float value) {
        int degrees = (int) (((value * 180 / Math.PI) + 360) % 360);
        if (degrees > 180)
            degrees = (degrees - 360) % 360;
        return degrees;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(value, max));
    }
}
