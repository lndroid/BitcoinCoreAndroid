package org.lndroid.bitcoincore;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.File;

public class EditViewModel extends AndroidViewModel {

    private String type_;
    private MutableLiveData<String> value_ = new MutableLiveData<>();

    public EditViewModel(@NonNull Application application) {
        super(application);
    }

    void setType(String type) {
        type_ = type;
        if (type_.equals("cmdline")) {
            SharedPreferences prefs = getApplication().getSharedPreferences(Common.PREFS_FILE, Context.MODE_PRIVATE);
            value_.setValue(prefs.getString("cmdline", Common.DEFAULT_CMDLINE));
        } else {
            DataFile df = new DataFile(Common.getConfFile(getApplication()));
            value_.setValue(df.read());
        }
    }

    void setValue(String d) {
        value_.setValue(d);
        if (type_.equals("cmdline")) {
            SharedPreferences.Editor e = getApplication().getSharedPreferences(Common.PREFS_FILE, Context.MODE_PRIVATE).edit();
            e.putString("cmdline", d);
            e.apply();
        } else {
            DataFile df = new DataFile(Common.getConfFile(getApplication()));
            df.write(d);
        }
    }

    LiveData<String> value() {
        return value_;
    }
}
