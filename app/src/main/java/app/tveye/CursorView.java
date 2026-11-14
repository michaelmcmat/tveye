package app.tveye;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;

/**
 * A mouse pointer driven by the remote's arrow keys. Movement starts with a small nudge for precise
 * aiming and accelerates while the key is held. When the pointer is pinned at a screen edge and the
 * key keeps pushing, the overflow is reported so the page can scroll.
 */
public final class CursorView extends View implements Choreographer.FrameCallback {

    public interface Listener {
        /** The pointer moved to (x, y) in this view's coordinates. */
        void onCursorMoved(float x, float y);

        /** The pointer is pinned at an edge and the key is still pushing by (dx, dy) pixels. */
        void onCursorEdge(float dx, float dy);
    }

    private static final float BASE_DP_PER_S = 260f;
    private static final float MAX_DP_PER_S = 1300f;
    private static final float ACCEL_SECONDS = 0.9f;
    private static final float NUDGE_DP = 5f;
    private static final long HIDE_AFTER_MS = 4000;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    private float x = -1, y = -1;
    private boolean left, right, up, down;
    private long moveStartNs, lastFrameNs;
    private boolean ticking;
    private long lastActivityMs;
    private boolean autoHide;
    private Listener listener;

    public CursorView(Context c, AttributeSet a) {
        super(c, a);
        density = getResources().getDisplayMetrics().density;
        fill.setColor(0xE6FFFFFF);
        ring.setColor(0xCC000000);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2.2f * density);
        dot.setColor(0xFF000000);
        setWillNotDraw(false);
    }

    public void setListener(Listener l) { listener = l; }

    /** Hide the pointer after a few idle seconds (video, full screen). */
    public void setAutoHide(boolean on) {
        autoHide = on;
        lastActivityMs = System.currentTimeMillis();
        invalidate();
    }

    public float cursorX() { ensurePlaced(); return x; }
    public float cursorY() { ensurePlaced(); return y; }

    private void ensurePlaced() {
        if (x < 0 && getWidth() > 0) { x = getWidth() / 2f; y = getHeight() / 2f; }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Start in the middle of the screen once the real size is known; keep inside after a resize.
        if (x < 0 || oldw == 0) { x = w / 2f; y = h / 2f; }
        else { x = Math.min(x, w - 1); y = Math.min(y, h - 1); }
    }

    public void wake() {
        lastActivityMs = System.currentTimeMillis();
        invalidate();
    }

    /** Arrow key down/up. Returns true if the key is an arrow. */
    public boolean onArrow(int keyCode, boolean isDown, boolean isRepeat) {
        boolean wasIdle = !(left || right || up || down);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: left = isDown; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: right = isDown; break;
            case KeyEvent.KEYCODE_DPAD_UP: up = isDown; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: down = isDown; break;
            default: return false;
        }
        wake();
        ensurePlaced();
        if (isDown && !isRepeat) {
            if (wasIdle) moveStartNs = System.nanoTime();
            // A short press moves a few pixels, for precise aiming.
            moveBy(dirX() * NUDGE_DP * density, dirY() * NUDGE_DP * density);
            if (!ticking) {
                ticking = true;
                lastFrameNs = 0;
                Choreographer.getInstance().postFrameCallbackDelayed(this, 140);
            }
        }
        return true;
    }

    private int dirX() { return (right ? 1 : 0) - (left ? 1 : 0); }
    private int dirY() { return (down ? 1 : 0) - (up ? 1 : 0); }

    @Override
    public void doFrame(long frameTimeNanos) {
        int dx = dirX(), dy = dirY();
        if (dx == 0 && dy == 0) { ticking = false; return; }
        if (lastFrameNs == 0) lastFrameNs = frameTimeNanos;
        float dt = Math.min(0.05f, (frameTimeNanos - lastFrameNs) / 1e9f);
        lastFrameNs = frameTimeNanos;
        float held = (frameTimeNanos - moveStartNs) / 1e9f;
        float k = Math.min(1f, held / ACCEL_SECONDS);
        float speed = (BASE_DP_PER_S + (MAX_DP_PER_S - BASE_DP_PER_S) * k * k) * density;
        float len = (dx != 0 && dy != 0) ? 0.7071f : 1f;
        moveBy(dx * speed * dt * len, dy * speed * dt * len);
        wake();
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void moveBy(float ddx, float ddy) {
        float w = getWidth(), h = getHeight();
        if (w <= 0) return;
        float nx = x + ddx, ny = y + ddy;
        float cx = Math.max(0, Math.min(w - 1, nx));
        float cy = Math.max(0, Math.min(h - 1, ny));
        float overX = nx - cx, overY = ny - cy;
        x = cx; y = cy;
        invalidate();
        if (listener != null) {
            listener.onCursorMoved(x, y);
            if (overX != 0 || overY != 0) listener.onCursorEdge(overX, overY);
        }
    }

    public void stopAll() { left = right = up = down = false; }

    @Override
    protected void onDraw(Canvas canvas) {
        ensurePlaced();
        if (x < 0) return;
        if (autoHide && System.currentTimeMillis() - lastActivityMs > HIDE_AFTER_MS) return;
        float r = 11f * density;
        canvas.drawCircle(x, y, r, fill);
        canvas.drawCircle(x, y, r, ring);
        canvas.drawCircle(x, y, 2.4f * density, dot);
        if (autoHide) postInvalidateDelayed(HIDE_AFTER_MS + 50);
    }
}
