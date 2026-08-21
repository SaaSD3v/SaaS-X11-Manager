package com.termux.x11;

import android.content.SharedPreferences;
import android.graphics.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import com.termux.x11.input.InputEventSender;
import com.termux.x11.input.InputStrategyInterface;
import com.termux.x11.input.InputStub;
import com.termux.x11.input.RenderData;
import com.termux.x11.input.TapGestureDetector;

/**
 * Touchscreen/touchpad gesture bridge for the embedded LorieView.
 *
 * Hardware mice remain owned by EmbeddedDisplayHost. This controller only
 * handles TOOL_TYPE_FINGER events so the touchMode preference can select the
 * same three input models exposed by upstream Termux:X11 without bringing the
 * standalone MainActivity into the Manager.
 */
public final class EmbeddedTouchInputController {
    private static final float EPSILON = 0.001f;

    private final LorieView view;
    private final InputEventSender sender;
    private final RenderData renderData = new RenderData();
    private final float[] mappedPoint = new float[2];
    private final float[] matrixValues = new float[9];
    private final GestureListener gestureListener = new GestureListener();
    private final GestureDetector scroller;
    private final TapGestureDetector tapDetector;
    private final Handler gestureHandler = new Handler(Looper.getMainLooper());
    private final SharedPreferences preferenceStore;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;
    private final View.OnAttachStateChangeListener attachStateListener;

    private InputStrategyInterface strategy;
    private String inputMode = "";
    private boolean dragging;
    private boolean preferenceListenerRegistered;

    public EmbeddedTouchInputController(LorieView view) {
        if (view == null)
            throw new NullPointerException("view");

        this.view = view;
        this.sender = new InputEventSender(view);
        this.scroller = new GestureDetector(view.getContext(), gestureListener, null, false);
        this.scroller.setIsLongpressEnabled(false);
        this.tapDetector = new TapGestureDetector(view.getContext(), gestureListener);
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
                gestureHandler.removeCallbacksAndMessages(null);
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
        return event.getToolType(index) == MotionEvent.TOOL_TYPE_FINGER;
    }

    public void updateInputTransform(int screenWidth, int screenHeight, Matrix inputTransform) {
        if (inputTransform == null)
            return;

        inputTransform.getValues(matrixValues);
        renderData.scale.set(matrixValues[Matrix.MSCALE_X], matrixValues[Matrix.MSCALE_Y]);
        renderData.screenWidth = screenWidth;
        renderData.screenHeight = screenHeight;
        renderData.setInputTransform(inputTransform);
    }

    public boolean handle(MotionEvent event) {
        if (event == null || !view.connected())
            return false;

        view.requestFocus();
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN)
            view.requestUnbufferedDispatch(event);

        if (event.getDeviceId() >= 0) {
            sender.releaseStuckModifiers(event.getMetaState());
            sender.syncLockKeysState(event.getMetaState());
        }

        if (event.getActionMasked() == MotionEvent.ACTION_UP)
            EmbeddedDisplayHost.setCapturingEnabled(true);

        if ("3".equals(inputMode)) {
            sender.sendTouchEvent(event, renderData);
            return true;
        }

        if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            float scrollY = -100f * event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            float scrollX = -100f * event.getAxisValue(MotionEvent.AXIS_HSCROLL);
            sender.sendMouseWheelEvent(scrollX, scrollY);
            return true;
        }

        if (event.getActionMasked() == MotionEvent.ACTION_DOWN)
            dragging = false;

        boolean wasDragging = dragging;
        strategy.onMotionEvent(event);
        // Both detectors must see every event; short-circuiting either one leaves
        // multi-finger tap/long-press state stuck across gestures.
        scroller.onTouchEvent(event);
        tapDetector.onTouchEvent(event);

        if (event.getActionMasked() == MotionEvent.ACTION_UP && wasDragging)
            dragging = false;

        return true;
    }

    public void dispose() {
        unregisterPreferenceListener();
        view.removeOnAttachStateChangeListener(attachStateListener);
        gestureHandler.removeCallbacksAndMessages(null);
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
        sender.tapToMove = prefs.tapToMove.get();
        sender.scaleTouchpad = prefs.scaleTouchpad.get()
                && "1".equals(prefs.touchMode.get())
                && !"native".equals(prefs.displayResolutionMode.get());

        String requestedMode = prefs.touchMode.get();
        if (!"1".equals(requestedMode)
                && !"2".equals(requestedMode)
                && !"3".equals(requestedMode))
            requestedMode = "1";

        if (requestedMode.equals(inputMode) && strategy != null)
            return;

        gestureHandler.removeCallbacksAndMessages(null);
        dragging = false;
        inputMode = requestedMode;
        if ("2".equals(inputMode)) {
            strategy = new InputStrategyInterface.SimulatedTouchInputStrategy(
                    renderData,
                    sender,
                    view.getContext()
            );
        } else if ("1".equals(inputMode)) {
            strategy = new InputStrategyInterface.TrackpadInputStrategy(sender);
        } else {
            // Direct touch bypasses the mouse strategies in handle(). Keep a
            // harmless strategy instance so asynchronous detector callbacks can
            // never dereference null while a preference change is arriving.
            strategy = new InputStrategyInterface.NullInputStrategy();
        }
    }

    private boolean isTrackpadMode() {
        return "1".equals(inputMode);
    }

    private boolean isSimulatedTouchMode() {
        return "2".equals(inputMode);
    }

    private int mouseButtonFromPointerCount(int pointerCount) {
        switch (pointerCount) {
            case 1:
                return InputStub.BUTTON_LEFT;
            case 2:
                return InputStub.BUTTON_RIGHT;
            case 3:
                return InputStub.BUTTON_MIDDLE;
            default:
                return InputStub.BUTTON_UNDEFINED;
        }
    }

    private boolean screenPointLiesOutsideImageBoundary(float screenX, float screenY) {
        renderData.mapScreenPoint(screenX, screenY, mappedPoint);
        return mappedPoint[0] < -EPSILON
                || mappedPoint[0] > renderData.screenWidth + EPSILON
                || mappedPoint[1] < -EPSILON
                || mappedPoint[1] > renderData.screenHeight + EPSILON;
    }

    private void moveCursorToScreenPoint(float screenX, float screenY) {
        renderData.mapScreenPoint(screenX, screenY, mappedPoint);
        if (renderData.setCursorPosition(mappedPoint[0], mappedPoint[1]))
            sender.sendCursorMove(mappedPoint[0], mappedPoint[1], false);
    }

    private void moveCursorByOffset(float distanceX, float distanceY) {
        if (isTrackpadMode()) {
            sender.sendCursorMove(-distanceX, -distanceY, true);
            return;
        }

        if (isSimulatedTouchMode()) {
            android.graphics.PointF cursor = renderData.getCursorPosition();
            cursor.offset(-distanceX, -distanceY);
            float x = Math.max(0f, Math.min(cursor.x, renderData.screenWidth));
            float y = Math.max(0f, Math.min(cursor.y, renderData.screenHeight));
            if (renderData.setCursorPosition(x, y))
                sender.sendCursorMove(x, y, false);
        }
    }

    private final class GestureListener extends GestureDetector.SimpleOnGestureListener
            implements TapGestureDetector.OnTapListener {

        @Override
        public boolean onDown(MotionEvent event) {
            return true;
        }

        @Override
        public boolean onScroll(
                MotionEvent first,
                MotionEvent current,
                float distanceX,
                float distanceY
        ) {
            int pointerCount = current.getPointerCount();
            if (pointerCount >= 3)
                return true;

            if (pointerCount == 2) {
                if (!isTrackpadMode()
                        && first != null
                        && !screenPointLiesOutsideImageBoundary(first.getX(), first.getY())) {
                    moveCursorToScreenPoint(first.getX(), first.getY());
                }
                strategy.onScroll(distanceX, distanceY);
                return true;
            }

            if (isTrackpadMode()) {
                if (sender.scaleTouchpad) {
                    distanceX *= renderData.scale.x;
                    distanceY *= renderData.scale.y;
                }
                moveCursorByOffset(distanceX, distanceY);
            } else if (dragging && !screenPointLiesOutsideImageBoundary(current.getX(), current.getY())) {
                moveCursorToScreenPoint(current.getX(), current.getY());
            }
            return true;
        }

        @Override
        public void onTap(int pointerCount, float x, float y) {
            if (dragging)
                return;

            int button = mouseButtonFromPointerCount(pointerCount);
            if (button == InputStub.BUTTON_UNDEFINED)
                return;

            if (!isTrackpadMode()) {
                if (screenPointLiesOutsideImageBoundary(x, y))
                    return;
                moveCursorToScreenPoint(x, y);
            }

            if (button == InputStub.BUTTON_LEFT && sender.tapToMove && isTrackpadMode()) {
                gestureHandler.removeCallbacksAndMessages(null);
                gestureHandler.postDelayed(
                        () -> strategy.onTap(InputStub.BUTTON_LEFT),
                        ViewConfiguration.getDoubleTapTimeout()
                );
            } else {
                strategy.onTap(button);
            }
        }

        @Override
        public void onLongPress(int pointerCount, float x, float y) {
            int button = mouseButtonFromPointerCount(pointerCount);
            if (button == InputStub.BUTTON_UNDEFINED)
                return;

            if (!isTrackpadMode()) {
                if (screenPointLiesOutsideImageBoundary(x, y))
                    return;
                moveCursorToScreenPoint(x, y);
            }

            if (strategy.onPressAndHold(button, false))
                dragging = true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent event) {
            return sender.tapToMove && isTrackpadMode();
        }

        @Override
        public boolean onDoubleTapEvent(MotionEvent event) {
            if (!sender.tapToMove || !isTrackpadMode() || event.getPointerCount() != 1)
                return false;

            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                gestureHandler.removeCallbacksAndMessages(null);
                if (strategy.onPressAndHold(InputStub.BUTTON_LEFT, true))
                    dragging = true;
            }
            return true;
        }
    }
}
