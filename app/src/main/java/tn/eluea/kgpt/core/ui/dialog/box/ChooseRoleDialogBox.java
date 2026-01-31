/*
 * ChooseRoleDialogBox: shown when the user triggers RoleSwitch pattern.
 */
package tn.eluea.kgpt.core.ui.dialog.box;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import tn.eluea.kgpt.R;
import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.core.data.ConfigContainer;
import tn.eluea.kgpt.core.ui.dialog.DialogBoxManager;
import tn.eluea.kgpt.roles.RoleManager;
import tn.eluea.kgpt.ui.main.FloatingBottomSheet;

public class ChooseRoleDialogBox extends DialogBox {

    public ChooseRoleDialogBox(DialogBoxManager dialogManager, Activity parent,
                               android.os.Bundle inputBundle, ConfigContainer configContainer) {
        super(dialogManager, parent, inputBundle, configContainer);
    }

    @Override
    protected Dialog build() {
        Context ctx = getParent();
        FloatingBottomSheet sheet = new FloatingBottomSheet(ctx);

        View layout = LayoutInflater.from(sheet.getContext()).inflate(R.layout.dialog_choose_role, null);
        sheet.setContentView(layout);

        View btnBack = layout.findViewById(R.id.btn_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> sheet.dismiss());

        LinearLayout container = layout.findViewById(R.id.roles_container);
        if (container != null) {
            SPManager sp = SPManager.getInstance();
            String rolesJson = sp.getRolesJson();
            String activeId = sp.getActiveRoleId();
            List<RoleManager.Role> roles = RoleManager.loadRoles(rolesJson);

            LayoutInflater inflater = LayoutInflater.from(layout.getContext());
            container.removeAllViews();

            for (RoleManager.Role r : roles) {
                if (r == null) continue;

                View item = inflater.inflate(R.layout.item_model_option, container, false);
                TextView tv = item.findViewById(R.id.tv_model_name);
                ImageView iv = item.findViewById(R.id.iv_icon);
                CheckBox cb = item.findViewById(R.id.cb_selected);

                if (tv != null) tv.setText(r.name != null ? r.name : "");
                if (iv != null) iv.setImageResource(R.drawable.ic_command_filled);
                if (cb != null) cb.setChecked(r.id != null && r.id.equals(activeId));

                item.setOnClickListener(v -> {
                    String id = r.id != null ? r.id : RoleManager.DEFAULT_ROLE_ID;
                    sp.setActiveRoleId(id);
                    Toast.makeText(layout.getContext(),
                            layout.getContext().getString(R.string.role_selected_toast, (r.name != null ? r.name : "")),
                            Toast.LENGTH_SHORT).show();
                    sheet.dismiss();
                });

                container.addView(item);
            }
        }
        return sheet;
    }
}
