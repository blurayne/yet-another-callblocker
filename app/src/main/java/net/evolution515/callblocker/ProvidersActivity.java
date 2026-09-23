package net.evolution515.callblocker;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.evolution515.callblocker.data.YacbHolder;
import net.evolution515.callblocker.data.provider.Provider;
import net.evolution515.callblocker.data.provider.ProviderService;

/**
 * The places a number can be looked up, as the dialog about a call offers them.
 *
 * <p>The switch on a row is what that dialog shows, and the order here is the order the rows
 * appear in, so this screen is the menu of the caller info rather than a set of accounts.
 */
public class ProvidersActivity extends AppCompatActivity {

    public static Intent getIntent(Context context) {
        return new Intent(context, ProvidersActivity.class);
    }

    private final ProviderService providerService = YacbHolder.getProviderService();

    private final List<Provider> providers = new ArrayList<>();

    private ProviderAdapter adapter;
    private ItemTouchHelper touchHelper;

    /** Set while a row is being dragged, so the new order is written down once, at the end. */
    private boolean reordered;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_providers);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) actionBar.setDisplayHomeAsUpEnabled(true);

        adapter = new ProviderAdapter();

        RecyclerView list = findViewById(R.id.providersList);
        list.setAdapter(adapter);
        list.addItemDecoration(new CustomVerticalDivider(this));
        UiUtils.speedUpAnimations(list);

        touchHelper = new ItemTouchHelper(new ReorderCallback());
        touchHelper.attachToRecyclerView(list);
    }

    @Override
    protected void onStart() {
        super.onStart();

        reload(); // a provider may have been edited on the screen this one leads to
    }

    private void reload() {
        providers.clear();
        if (providerService != null) providers.addAll(providerService.getProviders());

        adapter.notifyDataSetChanged();
    }

    public void onAddClicked(View view) {
        startActivity(EditProviderActivity.getIntent(this, null));
    }

    /** Moving a row moves it in the dialog about a call. */
    private class ReorderCallback extends ItemTouchHelper.SimpleCallback {

        ReorderCallback() {
            super(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
        }

        @Override
        public boolean isLongPressDragEnabled() {
            return true;
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView,
                              @NonNull RecyclerView.ViewHolder viewHolder,
                              @NonNull RecyclerView.ViewHolder target) {
            int from = viewHolder.getBindingAdapterPosition();
            int to = target.getBindingAdapterPosition();

            if (from < 0 || to < 0 || from >= providers.size() || to >= providers.size()) {
                return false;
            }

            Collections.swap(providers, from, to);
            adapter.notifyItemMoved(from, to);

            reordered = true;

            return true;
        }

        @Override
        public long getAnimationDuration(@NonNull RecyclerView recyclerView, int animationType,
                                         float animateDx, float animateDy) {
            // a row that is let go settles in half the usual time, like the rest of the list
            return super.getAnimationDuration(
                    recyclerView, animationType, animateDx, animateDy) / 2;
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            // nothing is swiped away: a provider is deleted where it is edited
        }

        @Override
        public void clearView(@NonNull RecyclerView recyclerView,
                              @NonNull RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);

            if (!reordered) return;
            reordered = false;

            if (providerService != null) providerService.save(providers);
        }

    }

    private class ProviderAdapter extends RecyclerView.Adapter<ProviderAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.provider_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            holder.bind(providers.get(position));
        }

        @Override
        public int getItemCount() {
            return providers.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {

            final TextView name, address;
            final SwitchCompat enabledSwitch;
            final ImageView dragHandle;

            /** Kept, because binding a recycled row has to put it aside for a moment. */
            final CompoundButton.OnCheckedChangeListener enabledListener = (v, checked) -> {
                Provider provider = getProvider();
                if (provider == null || provider.isEnabled() == checked) return;

                provider.setEnabled(checked);
                if (providerService != null) providerService.save(provider);
            };

            @SuppressLint("ClickableViewAccessibility") // the handle drags, it doesn't click
            ViewHolder(@NonNull View itemView) {
                super(itemView);

                name = itemView.findViewById(R.id.name);
                address = itemView.findViewById(R.id.address);
                enabledSwitch = itemView.findViewById(R.id.enabledSwitch);
                dragHandle = itemView.findViewById(R.id.dragHandle);

                dragHandle.setOnTouchListener((v, event) -> {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        touchHelper.startDrag(this);
                    }
                    return false;
                });

                itemView.setOnClickListener(v -> {
                    Provider provider = getProvider();
                    if (provider != null) {
                        startActivity(EditProviderActivity.getIntent(
                                ProvidersActivity.this, provider.getId()));
                    }
                });
            }

            void bind(Provider provider) {
                name.setText(ProviderHelper.getName(ProvidersActivity.this, provider));
                address.setText(ProviderHelper.getAddress(ProvidersActivity.this, provider));

                // set without the listener: a recycled row would report a change that isn't one
                enabledSwitch.setOnCheckedChangeListener(null);
                enabledSwitch.setChecked(provider.isEnabled());
                enabledSwitch.setOnCheckedChangeListener(enabledListener);
            }

            /** The provider this row is showing right now, or null if the list moved on. */
            private Provider getProvider() {
                int position = getBindingAdapterPosition();

                return position >= 0 && position < providers.size()
                        ? providers.get(position) : null;
            }

        }
    }

}
