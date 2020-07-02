package org.lndroid.bitcoincore;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

public class MainViewModel extends AndroidViewModel {

    private Messenger daemon_;
    private boolean isBound_;
    private MutableLiveData<String> status_ = new MutableLiveData<>();
    private MutableLiveData<String> result_ = new MutableLiveData<>();
    private MutableLiveData<Boolean> autoStart_ = new MutableLiveData<>();

    public MainViewModel(@NonNull Application a) {
        super(a);

        // ensure worker is scheduled
        AutoStartWorker.schedule(getApplication());

        // connect
        doBindService();

        // get flag value
        SharedPreferences prefs = getApplication().getSharedPreferences(Common.PREFS_FILE, Context.MODE_PRIVATE);
        boolean autoStart = prefs.getBoolean("isAutoStart", false);
        autoStart_.setValue(autoStart);
    }

    private Messenger client_ = new Messenger(new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(@NonNull Message msg) {
            Bundle b = msg.getData();
            String data = b.getString("data");
            switch (msg.what) {
                case DaemonService.MSG_START:
                    Toast.makeText(getApplication(), "Start result: "+data, Toast.LENGTH_LONG).show();
                    return true;
                case DaemonService.MSG_STOP:
                    Toast.makeText(getApplication(), "Stop result: "+data, Toast.LENGTH_LONG).show();
                    return true;
                case DaemonService.MSG_STATUS:
                    status_.setValue(data);
                    return true;
                case DaemonService.MSG_ERROR:
                    result_.setValue(data);
                    return true;
            }
            return false;
        }
    }));

    private ServiceConnection connection_ = new ServiceConnection() {
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {

            // This is called when the connection with the service has been
            // established, giving us the service object we can use to
            // interact with the service.  We are communicating with our
            // service through an IDL interface, so get a client-side
            // representation of that from the raw service object.
            daemon_ = new Messenger(service);

            // Register ourselves at this daemon
            try {
                Message msg = Message.obtain(null, DaemonService.MSG_STATUS);
                msg.replyTo = client_;
                daemon_.send(msg);

                // not really useful
//                Toast.makeText(MainActivity.this, "Connected to daemon service",
//                        Toast.LENGTH_SHORT).show();
            } catch (RemoteException e) {
                // In this case the service has crashed before we could even
                // do anything with it; we can count on soon being
                // disconnected (and then reconnected if it can be restarted)
                // so there is no need to do anything here.
                Toast.makeText(getApplication(), "Failed to get status of daemon", Toast.LENGTH_LONG).show();
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            // This is called when the connection with the service has been
            // unexpectedly disconnected -- that is, its process crashed.
            daemon_ = null;

            // Whenever we stop the daemon it will force it's process to
            // terminate so a disconnect is expected after a 'stop' call
            status_.setValue("Status: disconnected");

            // reconnect
            doUnbindService();
            doBindService();
        }
    };

    private void doBindService() {
        if (getApplication().bindService(new Intent(getApplication(), DaemonService.class), connection_, Context.BIND_AUTO_CREATE)) {
            isBound_ = true;
        } else {
            Toast.makeText(getApplication(), "Failed to bind to daemon", Toast.LENGTH_LONG).show();
        }
    }

    private void doUnbindService() {
        if (isBound_) {
            // Detach our existing connection.
            getApplication().unbindService(connection_);
            isBound_ = false;
        }
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        doUnbindService();
    }

    private void send(int what) {
        if (daemon_ == null) {
            Toast.makeText(getApplication(), "Not connected", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            Message msg = Message.obtain(null, what);
            msg.replyTo = client_;
            daemon_.send(msg);
        } catch (RemoteException e) {
            Toast.makeText(getApplication(), "Failed to send to daemon: "+e.getLocalizedMessage(), Toast.LENGTH_LONG).show();
        }
    }

    void startDaemon() {
        send(DaemonService.MSG_START);
    }

    void stopDaemon() {
        send(DaemonService.MSG_STOP);
    }

    LiveData<String> status() { return status_; }
    LiveData<String> result() { return result_; }
    LiveData<Boolean> autoStart() { return autoStart_; }

    void setAutoStart(boolean s) {
        if (s != autoStart_.getValue()){
            autoStart_.setValue(s);

            SharedPreferences.Editor e = getApplication().getSharedPreferences(Common.PREFS_FILE, Context.MODE_PRIVATE).edit();
            e.putBoolean("isAutoStart", s);
            e.apply();
        }
    }

    void startClient(String command) {
        final ProcessWorker worker = new ProcessWorker(
                getApplication(), "libbitcoin-cli.so", command, false);
        worker.setCallbackExecutor(ContextCompat.getMainExecutor(getApplication()));
        worker.setCallback(new Runnable() {
            @Override
            public void run() {
                result_.setValue(worker.result());
            }
        });
        worker.start();
    }

}
