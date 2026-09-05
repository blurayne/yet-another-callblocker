package dummydomain.yetanothercallblocker;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** The rows of the whitelist screen. */
public class WhitelistAdapter extends RecyclerView.Adapter<WhitelistAdapter.ViewHolder> {

    public interface Listener {
        void onEntry(String entry);
    }

    private final Listener clickListener;
    private final Listener removeListener;

    private List<String> entries = new ArrayList<>();

    public WhitelistAdapter(Listener clickListener, Listener removeListener) {
        this.clickListener = clickListener;
        this.removeListener = removeListener;
    }

    public void setEntries(List<String> newEntries) {
        List<String> oldEntries = entries;
        entries = new ArrayList<>(newEntries);

        DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return oldEntries.size();
            }

            @Override
            public int getNewListSize() {
                return entries.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return oldEntries.get(oldItemPosition).equals(entries.get(newItemPosition));
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                return true; // an entry is nothing but its text
            }
        }).dispatchUpdatesTo(this);
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
        holder.entry.setText(entries.get(position));
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    private String getEntry(RecyclerView.ViewHolder holder) {
        int position = holder.getBindingAdapterPosition();
        return position != RecyclerView.NO_POSITION ? entries.get(position) : null;
    }

    class ViewHolder extends RecyclerView.ViewHolder {

        final TextView entry;

        ViewHolder(@NonNull View itemView) {
            super(itemView);

            entry = itemView.findViewById(R.id.entry);

            itemView.setOnClickListener(v -> {
                String entry = getEntry(this);
                if (entry != null) clickListener.onEntry(entry);
            });

            itemView.findViewById(R.id.remove).setOnClickListener(v -> {
                String entry = getEntry(this);
                if (entry != null) removeListener.onEntry(entry);
            });
        }

    }

}
