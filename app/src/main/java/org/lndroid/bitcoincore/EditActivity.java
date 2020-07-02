package org.lndroid.bitcoincore;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

public class EditActivity extends AppCompatActivity {

    private EditViewModel model_;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit);

        Intent in = getIntent();
        String type = in.getStringExtra("type");

        model_ = new ViewModelProvider(this).get(EditViewModel.class);
        model_.setType(type);

        model_.value().observe(this, new Observer<String>() {
            @Override
            public void onChanged(String s) {
                EditText e = findViewById(R.id.value);
                e.setText(s);
            }
        });

        TextView t = findViewById(R.id.title);
        EditText e = findViewById(R.id.value);
        if (type.equals("cmdline")) {
            e.setHint("-debug=1 -par=1 ... (see -help)");
            t.setText("Command");
        } else {
            e.setHint("debug=1\nprune=1\n...");
            t.setText("Config");
        }

        Button b = findViewById(R.id.ok);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                EditText e = findViewById(R.id.value);
                model_.setValue(e.getText().toString());
                finish();
            }
        });
        b = findViewById(R.id.cancel);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        b = findViewById(R.id.help);
        b.setVisibility(type.equals("cmdline") ? View.VISIBLE : View.GONE);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                model_.setValue("-help");
            }
        });
        b = findViewById(R.id.def);
        b.setVisibility(type.equals("cmdline") ? View.VISIBLE : View.GONE);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                model_.setValue(Common.DEFAULT_CMDLINE);
            }
        });
    }
}
