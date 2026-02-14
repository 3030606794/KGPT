package tn.eluea.kgpt.ui.lab.floatingball;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import tn.eluea.kgpt.R;

/**
 * Activity wrapper so the Floating Ball settings can be opened from LabActivity (legacy) as well.
 */
public class FloatingBallActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_floating_ball);

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.container, new FloatingBallFragment())
                    .commit();
        }
    }
}
