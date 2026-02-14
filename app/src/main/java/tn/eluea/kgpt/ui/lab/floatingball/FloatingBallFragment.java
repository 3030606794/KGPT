package tn.eluea.kgpt.ui.lab.floatingball;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

import tn.eluea.kgpt.R;
import tn.eluea.kgpt.features.floatingball.FloatingBallKeys;
import tn.eluea.kgpt.features.floatingball.FloatingBallService;
import tn.eluea.kgpt.provider.ConfigClient;

/**
 * Lab settings for the floating pad entrance.
 *
 * The pad is fixed on screen (no auto-snap / hide). Users can adjust:
 * - Enable / disable
 * - X/Y position (0..1000 normalized)
 * - Width/Height in dp
 */
public class FloatingBallFragment extends Fragment {

    private ConfigClient client;

    private TextView tvPermission;
    private MaterialButton btnGrant;

    private MaterialSwitch switchPad;
    private Slider sliderX;
    private Slider sliderY;
    private Slider sliderW;
    private Slider sliderH;
    private TextView tvPosLabel;
    private TextView tvSizeLabel;
    private MaterialButton btnReset;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_floating_ball, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        client = new ConfigClient(requireContext());

        view.findViewById(R.id.btn_back).setOnClickListener(v -> {
            try {
                if (getParentFragmentManager().getBackStackEntryCount() > 0) {
                    getParentFragmentManager().popBackStack();
                } else if (getActivity() != null) {
                    getActivity().finish();
                }
            } catch (Throwable t) {
                if (getActivity() != null) getActivity().finish();
            }
        });

        tvPermission = view.findViewById(R.id.tv_permission_status);
        btnGrant = view.findViewById(R.id.btn_grant_permission);
        switchPad = view.findViewById(R.id.switch_pad);
        sliderX = view.findViewById(R.id.slider_pad_x);
        sliderY = view.findViewById(R.id.slider_pad_y);
        sliderW = view.findViewById(R.id.slider_pad_w);
        sliderH = view.findViewById(R.id.slider_pad_h);
        tvPosLabel = view.findViewById(R.id.tv_pos_label);
        tvSizeLabel = view.findViewById(R.id.tv_size_label);
        btnReset = view.findViewById(R.id.btn_reset_pad);

        btnGrant.setOnClickListener(v -> openOverlayPermission());

        bindFromPrefs();
        wireListeners();
        refreshPermissionUi();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshPermissionUi();
        FloatingBallService.sync(requireContext());
    }

    private void refreshPermissionUi() {
        boolean ok = Settings.canDrawOverlays(requireContext());
        tvPermission.setText(ok ? getString(R.string.ui_overlay_permission_granted)
                : getString(R.string.ui_overlay_permission_missing));
        btnGrant.setText(ok ? getString(R.string.ui_manage) : getString(R.string.ui_grant));
    }

    private void openOverlayPermission() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + requireContext().getPackageName()));
            startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    private void bindFromPrefs() {
        boolean enabled = client.getBoolean(FloatingBallKeys.KEY_PAD_ENABLED, false);
        int x = client.getInt(FloatingBallKeys.KEY_PAD_X, FloatingBallKeys.POS_UNSET);
        int y = client.getInt(FloatingBallKeys.KEY_PAD_Y, FloatingBallKeys.POS_UNSET);
        int w = client.getInt(FloatingBallKeys.KEY_PAD_W_DP, FloatingBallKeys.DEFAULT_PAD_W_DP);
        int h = client.getInt(FloatingBallKeys.KEY_PAD_H_DP, FloatingBallKeys.DEFAULT_PAD_H_DP);

        // Default to a sensible center-right position if unset.
        sliderX.setValue((x == FloatingBallKeys.POS_UNSET) ? 800 : x);
        sliderY.setValue((y == FloatingBallKeys.POS_UNSET) ? 550 : y);
        sliderW.setValue(w);
        sliderH.setValue(h);
        switchPad.setChecked(enabled);

        updateLabels();
    }

    private void updateLabels() {
        tvPosLabel.setText(getString(R.string.ui_pos_fmt,
                (int) sliderX.getValue(), (int) sliderY.getValue()));
        tvSizeLabel.setText(getString(R.string.ui_size_fmt,
                (int) sliderW.getValue(), (int) sliderH.getValue()));
    }

    private void wireListeners() {
        switchPad.setOnCheckedChangeListener((b, isChecked) -> {
            if (isChecked && !Settings.canDrawOverlays(requireContext())) {
                switchPad.setChecked(false);
                openOverlayPermission();
                return;
            }
            client.putBoolean(FloatingBallKeys.KEY_PAD_ENABLED, isChecked);
            FloatingBallService.sync(requireContext());
        });

        Slider.OnChangeListener posListener = (slider, value, fromUser) -> {
            if (!fromUser) return;
            client.putInt(FloatingBallKeys.KEY_PAD_X, (int) sliderX.getValue());
            client.putInt(FloatingBallKeys.KEY_PAD_Y, (int) sliderY.getValue());
            updateLabels();
            FloatingBallService.sync(requireContext());
        };
        sliderX.addOnChangeListener(posListener);
        sliderY.addOnChangeListener(posListener);

        Slider.OnChangeListener sizeListener = (slider, value, fromUser) -> {
            if (!fromUser) return;
            client.putInt(FloatingBallKeys.KEY_PAD_W_DP, (int) sliderW.getValue());
            client.putInt(FloatingBallKeys.KEY_PAD_H_DP, (int) sliderH.getValue());
            updateLabels();
            FloatingBallService.sync(requireContext());
        };
        sliderW.addOnChangeListener(sizeListener);
        sliderH.addOnChangeListener(sizeListener);

        btnReset.setOnClickListener(v -> {
            boolean enabledNow = client.getBoolean(FloatingBallKeys.KEY_PAD_ENABLED, false);
            client.putBoolean(FloatingBallKeys.KEY_PAD_ENABLED, enabledNow);
            client.putInt(FloatingBallKeys.KEY_PAD_X, FloatingBallKeys.POS_UNSET);
            client.putInt(FloatingBallKeys.KEY_PAD_Y, FloatingBallKeys.POS_UNSET);
            client.putInt(FloatingBallKeys.KEY_PAD_W_DP, FloatingBallKeys.DEFAULT_PAD_W_DP);
            client.putInt(FloatingBallKeys.KEY_PAD_H_DP, FloatingBallKeys.DEFAULT_PAD_H_DP);
            bindFromPrefs();
            FloatingBallService.sync(requireContext());
        });
    }
}
