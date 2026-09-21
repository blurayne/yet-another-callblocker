package dummydomain.yetanothercallblocker;

import android.os.Bundle;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

/**
 * How the thing works, in the app rather than in a wiki.
 *
 * <p>Two questions come up again and again: where the numbers the app knows come from, and
 * which of the lists wins when several of them have something to say about the same call.
 * Both are answered by what the code does, so both are answered here.
 */
public class HelpActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_help);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) actionBar.setDisplayHomeAsUpEnabled(true);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

}
