/*
 * Copyright (C) 2024-2025 Amr Aldeeb @Eluea
 *
 * This file is part of KGPT - a fork of KeyboardGPT.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package tn.eluea.kgpt.ui.main.adapters;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import tn.eluea.kgpt.R;
import tn.eluea.kgpt.instruction.command.GenerativeAICommand;
import tn.eluea.kgpt.instruction.command.InlineAskCommand;

public class CommandsAdapter extends RecyclerView.Adapter<CommandsAdapter.CommandViewHolder> {

    private static final String PREF_AMOLED = "amoled_mode";
    private static final String PREF_THEME = "theme_mode";

    private List<GenerativeAICommand> commands;
    private final OnCommandClickListener listener;

    // InlineAskCommand is always present in the UI as the first item.
    private boolean showBuiltInCommands = true;
    private boolean isAmoledMode = false;

    public interface OnCommandClickListener {
        void onCommandClick(GenerativeAICommand command, int position);
    }

    public CommandsAdapter(List<GenerativeAICommand> commands, OnCommandClickListener listener) {
        this.commands = commands;
        this.listener = listener;
    }

    public void updateCommands(List<GenerativeAICommand> newCommands) {
        this.commands = newCommands;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public CommandViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Check AMOLED mode from preferences
        Context context = parent.getContext();
        SharedPreferences prefs = context.getSharedPreferences("keyboard_gpt_ui", Context.MODE_PRIVATE);
        boolean isDarkMode = prefs.getBoolean(PREF_THEME, false);
        isAmoledMode = isDarkMode && prefs.getBoolean(PREF_AMOLED, false);

        View view = LayoutInflater.from(context).inflate(R.layout.item_command, parent, false);
        return new CommandViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull CommandViewHolder holder, int position) {
        GenerativeAICommand command = getCommandAtPosition(position);
        holder.bind(command, position);
    }

    @Override
    public int getItemCount() {
        int count = commands != null ? commands.size() : 0;
        if (showBuiltInCommands) {
            count += 1; // InlineAskCommand
        }
        return count;
    }

    private GenerativeAICommand getCommandAtPosition(int adapterPosition) {
        if (showBuiltInCommands) {
            if (adapterPosition == 0) {
                return InlineAskCommand.getInstance();
            }
            return commands.get(adapterPosition - 1);
        }
        return commands.get(adapterPosition);
    }

    private int getActualPosition(int adapterPosition) {
        return showBuiltInCommands ? adapterPosition - 1 : adapterPosition;
    }

    private int getBuiltinDescriptionResId(String prefixLower) {
        if (prefixLower == null) return 0;
        switch (prefixLower) {
            case "tr":
            case "translate":
                return R.string.ui_cmd_desc_tr;
            case "fix":
                return R.string.ui_cmd_desc_fix;
            case "short":
            case "summarize":
                return R.string.ui_cmd_desc_short;
            case "formal":
                return R.string.ui_cmd_desc_formal;
            case "casual":
                return R.string.ui_cmd_desc_casual;
            case "reply":
                return R.string.ui_cmd_desc_reply;
            case "email":
                return R.string.ui_cmd_desc_email;
            case "explain":
                return R.string.ui_cmd_desc_explain;
            case "code":
                return R.string.ui_cmd_desc_code;
            case "emoji":
                return R.string.ui_cmd_desc_emoji;
            default:
                return 0;
        }
    }

    class CommandViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvCommandName;
        private final TextView tvCommandDescription;
        private final TextView tvCommandExample;

        CommandViewHolder(@NonNull View itemView) {
            super(itemView);
            tvCommandName = itemView.findViewById(R.id.tv_command_name);
            tvCommandDescription = itemView.findViewById(R.id.tv_command_description);
            tvCommandExample = itemView.findViewById(R.id.tv_command_example);
        }

        void bind(GenerativeAICommand command, int adapterPosition) {
            Context ctx = itemView.getContext();
            boolean isInlineAsk = InlineAskCommand.isInlineAskCommand(command);

            String prefix = command.getCommandPrefix() == null ? "" : command.getCommandPrefix();
            tvCommandName.setText("/" + prefix);

            if (isInlineAsk) {
                // Built-in /ask command
                tvCommandName.setText("/" + prefix + ctx.getString(R.string.built_in_indicator));
                tvCommandDescription.setText(ctx.getString(R.string.ask_command_description));
                tvCommandDescription.setVisibility(View.VISIBLE);

                if (tvCommandExample != null) {
                    tvCommandExample.setText(ctx.getString(R.string.ask_command_example));
                    tvCommandExample.setVisibility(View.VISIBLE);
                    if (isAmoledMode) {
                        tvCommandExample.setBackgroundResource(R.drawable.bg_example_chip_amoled);
                    }
                }

                itemView.setOnClickListener(v -> {
                    if (listener != null) {
                        listener.onCommandClick(command, -1); // -1 indicates built-in
                    }
                });
                return;
            }

            // Default / built-in commands should KEEP the internal prompt (tweakMessage) in English,
            // but the UI description should be localized.
            String prefixLower = prefix.toLowerCase();
            int descResId = getBuiltinDescriptionResId(prefixLower);
            String tweakMsg = command.getTweakMessage();

            if (descResId != 0) {
                tvCommandDescription.setText(ctx.getString(descResId));
                tvCommandDescription.setVisibility(View.VISIBLE);
            } else if (tweakMsg != null && !tweakMsg.trim().isEmpty()) {
                // Custom command: show user's system message
                tvCommandDescription.setText(tweakMsg);
                tvCommandDescription.setVisibility(View.VISIBLE);
            } else {
                tvCommandDescription.setText(ctx.getString(R.string.title_custom_command));
                tvCommandDescription.setVisibility(View.VISIBLE);
            }

            if (tvCommandExample != null) {
                String example = getExampleForCommand(prefixLower, prefix);
                tvCommandExample.setText(ctx.getString(R.string.ui_example_prefix, example));
                tvCommandExample.setVisibility(View.VISIBLE);
                if (isAmoledMode) {
                    tvCommandExample.setBackgroundResource(R.drawable.bg_example_chip_amoled);
                }
            }

            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onCommandClick(command, getActualPosition(adapterPosition));
                }
            });
        }

        private String getExampleForCommand(String prefixLower, String originalPrefix) {
            // Note: We keep the trigger symbol as "$" in examples.
            // The actual symbol can be customized in Triggers tab.
            boolean isZh = java.util.Locale.getDefault().getLanguage().equals("zh");

            if (isZh) {
                if ("tr".equals(prefixLower) || prefixLower.contains("translate")) {
                    return "你的文本 /" + originalPrefix + "$";
                }
                if (prefixLower.contains("fix") || prefixLower.contains("grammar")) {
                    return "我 有 一 个 苹果 /" + originalPrefix + "$";
                }
                if (prefixLower.contains("summar") || "short".equals(prefixLower)) {
                    return "（长文本） /" + originalPrefix + "$";
                }
                if (prefixLower.contains("formal") || prefixLower.contains("email")) {
                    return "明天的会议 /" + originalPrefix + "$";
                }
                if (prefixLower.contains("reply")) {
                    return "帮我回一句 /" + originalPrefix + "$";
                }
                if (prefixLower.contains("explain")) {
                    return "量子物理 /" + originalPrefix + "$";
                }
                if (prefixLower.contains("code")) {
                    return "数组排序 /" + originalPrefix + "$";
                }
                if (prefixLower.contains("emoji")) {
                    return "今天心情不错 /" + originalPrefix + "$";
                }
                return "你的文本 /" + originalPrefix + "$";
            }

            if ("tr".equals(prefixLower) || prefixLower.contains("translate")) {
                return "your text /" + originalPrefix + "$";
            }
            if (prefixLower.contains("fix") || prefixLower.contains("grammar")) {
                return "I has a apple /" + originalPrefix + "$";
            }
            if (prefixLower.contains("summar") || "short".equals(prefixLower)) {
                return "[long text] /" + originalPrefix + "$";
            }
            if (prefixLower.contains("formal") || prefixLower.contains("email")) {
                return "meeting tomorrow /" + originalPrefix + "$";
            }
            if (prefixLower.contains("reply")) {
                return "reply to this /" + originalPrefix + "$";
            }
            if (prefixLower.contains("explain")) {
                return "Quantum physics /" + originalPrefix + "$";
            }
            if (prefixLower.contains("code")) {
                return "sort array /" + originalPrefix + "$";
            }
            if (prefixLower.contains("emoji")) {
                return "make this fun /" + originalPrefix + "$";
            }
            return "your text /" + originalPrefix + "$";
        }
    }
}
