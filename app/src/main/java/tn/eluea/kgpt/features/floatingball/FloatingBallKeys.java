package tn.eluea.kgpt.features.floatingball;

/**
 * Preference keys for the experimental floating entrance.
 *
 * v4 design: a single fixed "floating pad" (rectangle) that supports gestures:
 * - Tap: open AI chat input dialog
 * - Long press: copy the entire current input field
 * - Swipe up/down/left/right: trigger different actions
 */
public final class FloatingBallKeys {
    private FloatingBallKeys() {}

    // Unified floating pad
    public static final String KEY_PAD_ENABLED = "floating_pad_enabled_v1";

    // Position (normalized 0..1000). Service maps to screen pixels.
    public static final String KEY_PAD_X = "floating_pad_x_1000_v1";
    public static final String KEY_PAD_Y = "floating_pad_y_1000_v1";

    // Size (dp)
    public static final String KEY_PAD_W_DP = "floating_pad_w_dp_v1";
    public static final String KEY_PAD_H_DP = "floating_pad_h_dp_v1";

    // Cached IME metrics (written by a manifest receiver so the pad can follow the keyboard
    // even when the main app process is not alive).
    public static final String KEY_PAD_IME_VISIBLE = "floating_pad_ime_visible_v1";
    public static final String KEY_PAD_IME_TOP = "floating_pad_ime_top_v1";
    public static final String KEY_PAD_IME_HEIGHT = "floating_pad_ime_height_v1";

    public static final int DEFAULT_PAD_W_DP = 120;
    public static final int DEFAULT_PAD_H_DP = 44;

    /** -1 means "not set" (service computes a sensible default). */
    public static final int POS_UNSET = -1;

    // ----------------
    // Legacy keys (kept for backward compatibility / migration)
    // ----------------
    /** @deprecated Replaced by {@link #KEY_PAD_ENABLED}. */
    @Deprecated public static final String KEY_AI_BALL_ENABLED = "floating_ball_ai_enabled_v1";
    /** @deprecated Replaced by {@link #KEY_PAD_ENABLED}. */
    @Deprecated public static final String KEY_COPY_BALL_ENABLED = "floating_ball_copy_enabled_v1";
}
