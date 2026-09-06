package dummydomain.yetanothercallblocker;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.selection.ItemDetailsLookup;
import androidx.recyclerview.selection.ItemKeyProvider;
import androidx.recyclerview.selection.SelectionTracker;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import dummydomain.yetanothercallblocker.data.WhitelistItem;

/**
 * The rows of the whitelist screen.
 *
 * <p>The list is short and kept in the settings rather than in the database, so it is held in
 * memory instead of being paged like the blacklist. An entry is identified by its pattern,
 * which is what makes it unique.
 */
public class WhitelistAdapter extends RecyclerView.Adapter<WhitelistAdapter.ViewHolder> {

    public interface Listener {
        void onItemClicked(WhitelistItem item);
    }

    private final Listener listener;

    private List<WhitelistItem> items = new ArrayList<>();

    private SelectionTracker<String> selectionTracker;

    public WhitelistAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setSelectionTracker(SelectionTracker<String> selectionTracker) {
        this.selectionTracker = selectionTracker;
    }

    public void setItems(List<WhitelistItem> newItems) {
        List<WhitelistItem> oldItems = items;
        items = new ArrayList<>(newItems);

        DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return oldItems.size();
            }

            @Override
            public int getNewListSize() {
                return items.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return oldItems.get(oldItemPosition).getPattern()
                        .equals(items.get(newItemPosition).getPattern());
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                return oldItems.get(oldItemPosition).equals(items.get(newItemPosition));
            }
        }).dispatchUpdatesTo(this);
    }

    /** The patterns of all the entries, for "select all". */
    public List<String> getAllKeys() {
        List<String> keys = new ArrayList<>(items.size());
        for (WhitelistItem item : items) {
            keys.add(item.getPattern());
        }
        return keys;
    }

    public ItemKeyProvider<String> getItemKeyProvider() {
        return new ItemKeyProvider<String>(ItemKeyProvider.SCOPE_MAPPED) {
            @Nullable
            @Override
            public String getKey(int position) {
                return position >= 0 && position < items.size()
                        ? items.get(position).getPattern() : null;
            }

            @Override
            public int getPosition(@NonNull String key) {
                for (int i = 0; i < items.size(); i++) {
                    if (items.get(i).getPattern().equals(key)) return i;
                }
                return RecyclerView.NO_POSITION;
            }
        };
    }

    public ItemDetailsLookup<String> getItemDetailsLookup(RecyclerView recyclerView) {
        return new ItemDetailsLookup<String>() {
            @Nullable
            @Override
            public ItemDetails<String> getItemDetails(@NonNull MotionEvent e) {
                View view = recyclerView.findChildViewUnder(e.getX(), e.getY());
                if (view != null) {
                    RecyclerView.ViewHolder holder = recyclerView.getChildViewHolder(view);
                    if (holder instanceof ViewHolder) {
                        return ((ViewHolder) holder).getItemDetails();
                    }
                }
                return null;
            }
        };
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.whitelist_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(items.get(position));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private WhitelistItem getItem(int position) {
        return position >= 0 && position < items.size() ? items.get(position) : null;
    }

    class ViewHolder extends RecyclerView.ViewHolder {

        final TextView name, pattern;

        ItemDetailsLookup.ItemDetails<String> itemDetails;

        ViewHolder(@NonNull View itemView) {
            super(itemView);

            name = itemView.findViewById(R.id.name);
            pattern = itemView.findViewById(R.id.pattern);

            itemView.setOnClickListener(v -> {
                WhitelistItem item = getItem(getBindingAdapterPosition());
                if (item != null) listener.onItemClicked(item);
            });
        }

        void bind(WhitelistItem item) {
            name.setText(item.getName());
            name.setVisibility(TextUtils.isEmpty(item.getName()) ? View.GONE : View.VISIBLE);

            pattern.setText(item.getPattern());

            itemView.setActivated(selectionTracker != null
                    && selectionTracker.isSelected(item.getPattern()));
        }

        ItemDetailsLookup.ItemDetails<String> getItemDetails() {
            if (itemDetails == null) {
                itemDetails = new ItemDetailsLookup.ItemDetails<String>() {
                    @Override
                    public int getPosition() {
                        return getBindingAdapterPosition();
                    }

                    @Nullable
                    @Override
                    public String getSelectionKey() {
                        WhitelistItem item = getItem(getBindingAdapterPosition());
                        return item != null ? item.getPattern() : null;
                    }
                };
            }
            return itemDetails;
        }

    }

}
