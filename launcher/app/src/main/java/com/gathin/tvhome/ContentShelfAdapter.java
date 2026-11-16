package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for cards within horizontal content shelves (Stremio Continue Watching & SmartTube Subscriptions).
 */
public class ContentShelfAdapter extends RecyclerView.Adapter<ContentShelfAdapter.CardViewHolder> {

    public interface ShelfListener {
        void onShelfUp(int shelfIndex);
        void onShelfDown(int shelfIndex);
    }

    private final Context context;
    private final List<MediaShelfItem> items;
    private final boolean isWidescreen;
    private final int shelfIndex;
    private final ShelfListener listener;

    public ContentShelfAdapter(Context context, List<MediaShelfItem> items, boolean isWidescreen,
                               int shelfIndex, ShelfListener listener) {
        this.context = context;
        this.items = new ArrayList<>(items);
        this.isWidescreen = isWidescreen;
        this.shelfIndex = shelfIndex;
        this.listener = listener;
    }

    public void updateItems(List<MediaShelfItem> newItems) {
        this.items.clear();
        if (newItems != null) this.items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public CardViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_shelf_card, parent, false);
        return new CardViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull CardViewHolder holder, int position) {
        MediaShelfItem item = items.get(position);
        float density = context.getResources().getDisplayMetrics().density;

        ViewGroup.LayoutParams lp = holder.container.getLayoutParams();
        if (item.isWidescreen) {
            lp.width = Math.round(200 * density);
            lp.height = Math.round(114 * density);
        } else {
            lp.width = Math.round(135 * density);
            lp.height = Math.round(195 * density);
        }
        holder.container.setLayoutParams(lp);

        holder.title.setText(item.title);
        holder.title.getLayoutParams().width = lp.width;
        holder.subtitle.setText(item.subtitle);
        holder.subtitle.getLayoutParams().width = lp.width;

        if (item.progressPercent > 0 && item.progressPercent < 100) {
            holder.progress.setVisibility(View.VISIBLE);
            holder.progress.setProgress(item.progressPercent);
        } else {
            holder.progress.setVisibility(View.GONE);
        }

        if (!item.isWidescreen) {
            // Stremio cards: if TV series episode (e.g. "S1 • E7"), show full episode badge; otherwise hide
            if (item.subtitle != null && item.subtitle.startsWith("S") && item.subtitle.contains("E")) {
                holder.badge.setVisibility(View.VISIBLE);
                holder.badge.setText(item.subtitle);
            } else {
                holder.badge.setVisibility(View.GONE);
            }
        } else {
            // SmartTube widescreen cards: show channel name
            if (item.subtitle != null && item.subtitle.contains("•")) {
                holder.badge.setVisibility(View.VISIBLE);
                holder.badge.setText(item.subtitle.split("•")[0].trim());
            } else {
                holder.badge.setVisibility(View.GONE);
            }
        }

        MediaShelfManager.loadPosterAsync(context, item.posterUrl, holder.poster);

        holder.container.setOnClickListener(v -> MediaShelfManager.launchMediaItem(context, item));

        holder.container.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (listener != null) listener.onShelfUp(shelfIndex);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (listener != null) listener.onShelfDown(shelfIndex);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && holder.getBindingAdapterPosition() == 0) {
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && holder.getBindingAdapterPosition() == items.size() - 1) {
                return true;
            }
            return false;
        });

        holder.container.setOnFocusChangeListener((v, focused) -> {
            v.animate()
                .scaleX(focused ? 1.06f : 1.0f)
                .scaleY(focused ? 1.06f : 1.0f)
                .translationZ(focused ? 8f * density : 0f)
                .setDuration(160)
                .start();
            holder.focusBorder.setVisibility(focused ? View.VISIBLE : View.GONE);
            holder.title.setTextColor(focused ? Color.WHITE : 0xD9FFFFFF);
            holder.title.setSelected(focused);
            if (focused && context instanceof MainActivity) {
                ((MainActivity) context).runOnUiThread(() -> {
                    View hero = ((MainActivity) context).findViewById(R.id.hero);
                    if (hero != null) {
                        hero.setVisibility(View.GONE);
                        hero.setAlpha(0f);
                    }
                    TextView heroName = ((MainActivity) context).findViewById(R.id.heroName);
                    if (heroName != null) heroName.setText("");
                });
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class CardViewHolder extends RecyclerView.ViewHolder {
        final FrameLayout container;
        final ImageView poster;
        final TextView badge;
        final ProgressBar progress;
        final View focusBorder;
        final TextView title;
        final TextView subtitle;

        CardViewHolder(@NonNull View itemView) {
            super(itemView);
            container = itemView.findViewById(R.id.cardContainer);
            poster = itemView.findViewById(R.id.posterImg);
            badge = itemView.findViewById(R.id.cardBadge);
            progress = itemView.findViewById(R.id.cardProgress);
            focusBorder = itemView.findViewById(R.id.cardFocusBorder);
            title = itemView.findViewById(R.id.cardTitle);
            subtitle = itemView.findViewById(R.id.cardSubtitle);

            float density = itemView.getResources().getDisplayMetrics().density;
            container.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 14f * density);
                }
            });
            container.setClipToOutline(true);
        }
    }
}
