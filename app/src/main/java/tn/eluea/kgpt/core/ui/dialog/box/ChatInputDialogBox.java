package tn.eluea.kgpt.core.ui.dialog.box;

import android.app.Activity;
import android.app.Dialog;
import android.os.Bundle;
import android.view.View;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import tn.eluea.kgpt.R;
import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.core.data.ConfigContainer;
import tn.eluea.kgpt.core.ui.dialog.DialogBoxManager;
import tn.eluea.kgpt.ui.UiInteractor;

/**
 * Simple AI chat input dialog used by the Floating Ball entrance.
 * It commits: <prompt> + <AI trigger symbol> into the active editor.
 */
public class ChatInputDialogBox extends DialogBox {

    public ChatInputDialogBox(DialogBoxManager dialogManager, Activity parent,
                              Bundle inputBundle, ConfigContainer configContainer) {
        super(dialogManager, parent, inputBundle, configContainer);
    }

    @Override
    protected Dialog build() {
        tn.eluea.kgpt.ui.main.FloatingBottomSheet sheet = new tn.eluea.kgpt.ui.main.FloatingBottomSheet(getContext());
        View layout = android.view.LayoutInflater.from(sheet.getContext()).inflate(R.layout.dialog_chat_input, null);

        MaterialButton btnCancel = layout.findViewById(R.id.btn_cancel);
        MaterialButton btnSend = layout.findViewById(R.id.btn_send);
        TextInputEditText et = layout.findViewById(R.id.et_prompt);

        btnCancel.setOnClickListener(v -> sheet.dismiss());

        btnSend.setOnClickListener(v -> {
            String prompt = et != null && et.getText() != null ? et.getText().toString() : "";
            if (prompt == null) prompt = "";
            prompt = prompt.trim();
            if (prompt.isEmpty()) {
                try { UiInteractor.getInstance().toastLong(getContext().getString(R.string.ui_enter_prompt)); } catch (Throwable ignored) {}
                return;
            }

            String trig = "$";
            try {
                SPManager sp = SPManager.getInstance();
                if (sp != null) trig = sp.getAiTriggerSymbol();
            } catch (Throwable ignored) {}

            final String commit = prompt + trig;

            // Important: dismiss first so the target app regains focus (IME reconnects),
            // then commit into the *previous* editor (not this dialog's EditText).
            try {
                sheet.setOnDismissListener(d -> {
                    try {
                        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                        h.postDelayed(() -> {
                            try {
                                android.content.Intent i = new android.content.Intent(UiInteractor.ACTION_FB_COMMIT_PROMPT);
                                i.putExtra(UiInteractor.EXTRA_FB_TEXT, commit);
                                getContext().sendBroadcast(i);
                            } catch (Throwable ignored2) {}
                        }, 250);
                    } catch (Throwable ignored2) {}
                });
            } catch (Throwable ignored) {}

            sheet.dismiss();
        });sheet.setContentView(layout);
        return sheet;
    }
}
