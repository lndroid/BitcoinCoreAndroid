package org.lndroid.bitcoincore;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private MainViewModel model_;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        model_ = new ViewModelProvider(this).get(MainViewModel.class);
        model_.status().observe(this, new Observer<String>() {
            @Override
            public void onChanged(String s) {
                TextView t = MainActivity.this.findViewById(R.id.status);
                t.setText("Status: " + s);
            }
        });
        model_.result().observe(this, new Observer<String>() {
            @Override
            public void onChanged(String s) {
                EditText t = MainActivity.this.findViewById(R.id.result);
                t.setText("Result: " + s);
            }
        });
        model_.autoStart().observe(this, new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean s) {
                CheckBox cb = findViewById(R.id.autoStart);
                cb.setChecked(s);
            }
        });

/*        SharedPreferences prefs = getSharedPreferences(Common.PREFS_FILE, Context.MODE_PRIVATE);

        String params = prefs.getString("cmdline", "");
        if (params.isEmpty()) {
            params = Common.DEFAULT_CMDLINE;
        }
        EditText t = findViewById(R.id.cmd);
        t.setText(params);
*/
        Button b = findViewById(R.id.start);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                EditText r = MainActivity.this.findViewById(R.id.result);
                r.setText("");
                model_.startDaemon();
            }
        });
        b = findViewById(R.id.stop);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                model_.stopDaemon();
            }
        });
        b = findViewById(R.id.client);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                client();
            }
        });
        b = findViewById(R.id.cmdline);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent in = new Intent(MainActivity.this, EditActivity.class);
                in.putExtra("type", "cmdline");
                startActivity(in);
            }
        });
        b = findViewById(R.id.config);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent in = new Intent(MainActivity.this, EditActivity.class);
                in.putExtra("type", "config");
                startActivity(in);
            }
        });
        b = findViewById(R.id.getinfo);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                EditText e = findViewById(R.id.cmd);
                e.setText("-getinfo");
            }
        });
        b = findViewById(R.id.help);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                EditText e = findViewById(R.id.cmd);
                e.setText("-help");
            }
        });

        CheckBox cb = findViewById(R.id.autoStart);
        cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                model_.setAutoStart(isChecked);
                if (isChecked)
                    Toast.makeText(MainActivity.this,
                            "Daemon will auto start when device is charging and on WiFi.", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void client() {
        EditText e = findViewById(R.id.cmd);
        model_.startClient(e.getText().toString());
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(e.getWindowToken(), 0);
        View r = findViewById(R.id.result);
        r.requestFocus();
        r.requestFocusFromTouch();
    }
}
