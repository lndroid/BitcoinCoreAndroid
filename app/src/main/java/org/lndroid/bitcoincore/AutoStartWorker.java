package org.lndroid.bitcoincore;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkerParameters;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.TimeUnit;

public class AutoStartWorker extends androidx.work.ListenableWorker {
    public static final String WORK_ID = "org.lndroid.bitcoincore.AutoStartWorker";
    private static final String TAG = "AutoStartWorker";

    private static final long WORK_INTERVAL = 15 * 60 * 1000; // 15 min

    private Handler handler_;
    private CallbackToFutureAdapter.Completer<Result> completer_;

    private ServiceConnection connection_ = new ServiceConnection() {
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {

            Log.i(TAG, "sending start command");
            Messenger daemon = new Messenger(service);
            try {
                Message msg = Message.obtain(null, DaemonService.MSG_START);
                daemon.send(msg);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send to daemon: "+e.getLocalizedMessage());
            }

            // pause to let daemon turn into foreground service so that
            // OS wouldn't kill our process. we can't block the current thread
            // bcs service will be started on the same thread
            Log.i(TAG, "waiting for service to start");
            handler_.postDelayed(new Runnable() {
                @Override
                public void run() {
                    // we're done
                    Log.i(TAG, "done");
                    AutoStartWorker.this.getApplicationContext().unbindService(connection_);

                    // ok
                    completer_.set(Result.success());
                }
            }, 5000);
        }

        public void onServiceDisconnected(ComponentName className) {
            // just unbind
            AutoStartWorker.this.getApplicationContext().unbindService(connection_);

            // ok too, we don't want worker to restart like crazy
            completer_.set(Result.success());
        }
    };

    public AutoStartWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
        handler_ = new Handler(getApplicationContext().getMainLooper());
    }

    @NonNull
    @Override
    public ListenableFuture<Result> startWork() {
        return CallbackToFutureAdapter.getFuture(new CallbackToFutureAdapter.Resolver<Result>() {
            @Override
            public Object attachCompleter(@NonNull CallbackToFutureAdapter.Completer<Result> completer) throws Exception {
                Log.i(TAG, "starting");

                // check autoStart and nextStartTime
                SharedPreferences prefs = getApplicationContext().getSharedPreferences(Common.PREFS_FILE, Context.MODE_PRIVATE);
                boolean autoStart = prefs.getBoolean("isAutoStart", false);
                long nextStartTime = prefs.getLong("nextStartTime", 0);
                Log.i(TAG, "autoStart "+autoStart+" nextStartTime "+nextStartTime+" now "+System.currentTimeMillis());

                if (!autoStart || nextStartTime > System.currentTimeMillis()) {
                    // terminate immediately
                    Log.i(TAG, "no need to auto-start now");
                    completer.set(Result.success());
                } else {
                    // save completer to be called when connection is established
                    completer_ = completer;

                    // bind to the service
                    Intent in = new Intent(getApplicationContext(), DaemonService.class);
                    if (!getApplicationContext().bindService(in, connection_, Context.BIND_AUTO_CREATE)) {

                        // don't want worker to restart on failures
                        completer_.set(Result.success());
                    } else {
                        // when connection is established, completer is
                        // called after we send the 'start' message to client
                    }
                }

                // just debug label
                return "AutoStartWorker client";
            }
        });
    }

    public static void schedule(Context ctx) {
        // only run when charging and on unmetered connection
        Constraints constraints = new Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .build();

        // create work request
        PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(
                AutoStartWorker.class, WORK_INTERVAL, TimeUnit.MILLISECONDS)
                .addTag(WORK_ID)
                .setConstraints(constraints)
                .build();

        WorkManager wm = WorkManager.getInstance(ctx);

        // cancel all existing work
        wm.cancelAllWorkByTag(WORK_ID);

        // we need to REPLACE the existing work otherwise all our
        // changes to the workId are ignored
        wm.enqueueUniquePeriodicWork(WORK_ID, ExistingPeriodicWorkPolicy.REPLACE, work);
    }

    public static class BootReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            // make sure auto-starter is scheduled
            schedule(context);
        }
    }
}
