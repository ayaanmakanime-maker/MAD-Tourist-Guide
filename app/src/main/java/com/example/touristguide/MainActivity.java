package com.example.touristguide;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.example.touristguide.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private int loadedTab = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Store.init(this);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(binding.container, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, bars.top, 0, 0);
            return insets;
        });
        ViewCompat.setOnApplyWindowInsetsListener(binding.nav, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(0, 0, 0, bars.bottom);
            return insets;
        });

        binding.nav.setOnItemSelectedListener(item -> {
            choose(item.getItemId());
            return true;
        });
        binding.nav.setOnItemReselectedListener(item -> choose(item.getItemId()));

        int tabId = savedInstanceState == null
                ? R.id.nav_explore
                : savedInstanceState.getInt("tab", R.id.nav_explore);
        choose(tabId);
        binding.nav.setSelectedItemId(tabId);
    }

    private void choose(int id) {
        if (loadedTab == id) return;
        loadedTab = id;
        if (id == R.id.nav_map) showTab(new MapTabFragment());
        else if (id == R.id.nav_plan) showTab(new PlanFragment());
        else if (id == R.id.nav_chat) showTab(new ChatFragment());
        else if (id == R.id.nav_more) showTab(new MoreFragment());
        else showTab(new ExploreFragment());
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt("tab", loadedTab);
        super.onSaveInstanceState(outState);
    }

    private void showTab(Fragment fragment) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.container, fragment)
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                .commit();
    }

    public void openDetails(Place place) {
        DetailSheet sheet = DetailSheet.newInstance(place);
        sheet.show(getSupportFragmentManager(), sheet.getTag());
    }
}
