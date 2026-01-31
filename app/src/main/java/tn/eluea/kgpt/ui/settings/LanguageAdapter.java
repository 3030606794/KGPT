/*
 * Copyright (c) 2025 Amr Aldeeb @Eluea
 * GitHub: https://github.com/Eluea
 * Telegram: https://t.me/Eluea
 *
 * Licensed under the GPLv3.
 */
package tn.eluea.kgpt.ui.settings;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;

import tn.eluea.kgpt.R;

public class LanguageAdapter extends RecyclerView.Adapter<LanguageAdapter.LanguageViewHolder> {

    public interface OnLanguageSelectedListener {
        void onLanguageSelected(LanguageItem language);
    }

    private List<LanguageItem> languages;
    private List<LanguageItem> originalLanguages;
    private OnLanguageSelectedListener listener;
    private Context context;

    public LanguageAdapter(Context context, List<LanguageItem> languages, OnLanguageSelectedListener listener) {
        this.context = context;
        this.languages = new ArrayList<>(languages);
        this.originalLanguages = new ArrayList<>(languages);
        this.listener = listener;
    }

    @NonNull
    @Override
    public LanguageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_language, parent, false);
        return new LanguageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull LanguageViewHolder holder, int position) {
        LanguageItem item = languages.get(position);
        holder.bind(item);
    }

    @Override
    public int getItemCount() {
        return languages.size();
    }

    public void filter(String query) {
        languages.clear();
        if (query.isEmpty()) {
            languages.addAll(originalLanguages);
        } else {
            String lowerQuery = query.toLowerCase();
            for (LanguageItem item : originalLanguages) {
                if (item.getName().toLowerCase().contains(lowerQuery) ||
                        item.getNativeName().toLowerCase().contains(lowerQuery)) {
                    languages.add(item);
                }
            }
        }
        notifyDataSetChanged();
    }

    class LanguageViewHolder extends RecyclerView.ViewHolder {
        TextView tvLanguageName;
        TextView tvLanguageNative;
        ImageView ivCheck;
        View rootView;

        public LanguageViewHolder(@NonNull View itemView) {
            super(itemView);
            tvLanguageName = itemView.findViewById(R.id.tv_language_name);
            tvLanguageNative = itemView.findViewById(R.id.tv_language_native);
            ivCheck = itemView.findViewById(R.id.iv_check);
            rootView = itemView;
        }

        public void bind(LanguageItem item) {
            tvLanguageName.setText(item.getName());

            if (item.getNativeName() == null || item.getNativeName().isEmpty()) {
                tvLanguageNative.setVisibility(View.GONE);
            } else {
                tvLanguageNative.setVisibility(View.VISIBLE);
                tvLanguageNative.setText(item.getNativeName());
            }

            int colorOnPrimaryContainer = MaterialColors.getColor(context,
                    com.google.android.material.R.attr.colorOnPrimaryContainer, 0);
            int colorOnSurface = MaterialColors.getColor(context,
                    com.google.android.material.R.attr.colorOnSurface, 0);
            int colorOnSurfaceVariant = MaterialColors.getColor(context,
                    com.google.android.material.R.attr.colorOnSurfaceVariant, 0);

            // Selected state handling
            if (item.isSelected()) {
                ivCheck.setVisibility(View.VISIBLE);

                // Highlighted background for selected item
                rootView.setBackgroundResource(R.drawable.bg_option_selected_rounded);

                // Text follows OnPrimaryContainer
                tvLanguageName.setTextColor(colorOnPrimaryContainer);
                tvLanguageNative.setTextColor(colorOnPrimaryContainer);
                tvLanguageNative.setAlpha(0.8f);
                ivCheck.setColorFilter(colorOnPrimaryContainer);
            } else {
                ivCheck.setVisibility(View.GONE);
                rootView.setBackgroundResource(R.drawable.ripple_settings_item_rounded);

                // Default colors
                tvLanguageName.setTextColor(colorOnSurface);
                tvLanguageNative.setTextColor(colorOnSurfaceVariant);
                tvLanguageNative.setAlpha(0.7f);
            }

            rootView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onLanguageSelected(item);
                }
            });
        }
    }
}
