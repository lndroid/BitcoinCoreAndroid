package org.lndroid.bitcoincore;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.concurrent.atomic.AtomicBoolean;

/* Service hosting the bitcoind daemon.
    - This service lives in a separate process ":daemon" which means the only
    way to talk to other app components is over IPC (Binder). The reason we need
    separate process is bcs both libbitcoin_daemon and libbitcoin_client share
    global variables (as those are not intended to live in a single process).
    - Service object is bind-able, created by OS whenever someone binds to it,
    which is only possible for the other components of this app bcs
    the service is not exported. If all clients unbind, OS will kill this service.
    - When MSG_START is received from a client, we call startForegroundService
    on ourselves to tell OS this service needs to live even if all clients
    disconnect, which results in onStartCommand call which starts the daemon in
    a separate thread.
    - We return SERVICE_NOT_STICKY from onStartCommand so that OS wouldn't restart
    the service with onStartCommand if it crashes (which is the normal way we terminate it)
    - When MSG_STOP is received, we send SIGINT to ourselves to tell bitcoind
    that it needs to shutdown.
    - When daemon thread terminates (due to it's internal error or SIGINT) worker
    will signal to service main thread with an atomic flag and a message,
    which causes main thread to send SIGKILL to make sure OS terminates this
    process - reusing it is impossible as finished daemon core is left in unspecified
    state and restarting it is bad.
    - If there were clients connected to the service when it was SIGKILLed, OS will
    restart the process hosting this service and clients will reconnect.
 */

public class DaemonService extends Service {

    private static final long PAUSE_INTERVAL = 30 * 60 * 1000; // 30 min

    // sent by client to start daemon, replied by daemon
    // after start attempted
    public static final int MSG_START = 1;

    // sent by client to stop daemon, replied by daemon
    // after stop attempted
    public static final int MSG_STOP = 2;

    // sent by client to check daemon status, sent by daemon
    // in reply and when it's started/stopped (to all registered clients)
    public static final int MSG_STATUS = 3;

    // sent by daemon if it dies with an error
    public static final int MSG_ERROR = 4;

    private static final String TAG = "DaemonService";
    private static final String NOTIFICATION_CHANNEL_DAEMON = "org.lndroid.bitcoincore.CHANNEL_DAEMON";
    private static final int NOTIFICATION_ID_DAEMON = 1;

    // atomic to let different threads attempt to start the daemon
    private AtomicBoolean started_ = new AtomicBoolean(false);

    // worker process
    private ProcessWorker worker_;

    // daemon status
    private String status_;

    // clients that call 'start' or 'status' are added here
    private LinkedList<WeakReference<Messenger>> clients_ = new LinkedList<>();

    // used by worker thread to signal to service thread that it's done,
    // and by client thread to pass daemon status to service thread
    private Handler childHandler_ = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(@NonNull Message msg) {
            if (msg.what != 0) {
                Log.i(TAG, "daemon status: " + msg.obj);
                String result = (String) msg.obj;
                onStatus(result);
            } else {
                Log.i(TAG, "daemon finished: " + msg.obj);
                String result = (String) msg.obj;
                onDone(result);
            }
            return true;
        }
    });

    // binder to accept messages from clients
    private Messenger messenger_ = new Messenger(new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(@NonNull Message msg) {
            switch (msg.what) {
                case MSG_START:
                    onMsgStart(msg.replyTo);
                    return true;
                case MSG_STOP:
                    onMsgStop(msg.replyTo);
                    return true;
                case MSG_STATUS:
                    onMsgStatus(msg.replyTo);
                    return true;
            }

            return false;
        }
    }));

    // helper
    private boolean send(Messenger client, int what, String data) {
        if (client == null)
            return false;

        try {
            Message msg = Message.obtain(null, what);
            Bundle b = new Bundle();
            b.putString("data", data);
            msg.setData(b);
            client.send(msg);
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to send message to client: " + e.getLocalizedMessage());
            return false;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return messenger_.getBinder();
    }

    private Notification createNotification() {
        // intents to start the app if notification is clicked
        Intent notificationIntent = new Intent(getApplicationContext(), MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                getApplicationContext(),
                0,
                notificationIntent,
                PendingIntent.FLAG_ONE_SHOT // replace existing pending intent
        );

        // notification itself
        NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), NOTIFICATION_CHANNEL_DAEMON)
                .setContentTitle("BitcoinCore daemon running:")
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                // NOTE: this is required!
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setDefaults(0) // don't ring or vibrate
//                .setPriority(priority)
                .setContentText(getStatus())
                ;

        return builder.build();
    }

    private boolean isAutoStart() {
        SharedPreferences prefs = getSharedPreferences(Common.PREFS_FILE, Context.MODE_PRIVATE);
        return prefs.getBoolean("isAutoStart", false);
    }

    private boolean isStartAllowed() {
        boolean stop = false;

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) {
            NetworkCapabilities cp = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (cp != null) {
                boolean unmetered = cp.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
                boolean internet = cp.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
                Log.i(TAG, "network state unmetered " + unmetered + " internet " + internet);
                stop |= !unmetered || !internet;
            }
        }

        BatteryManager bm = getSystemService(BatteryManager.class);
        if (bm != null) {
            Log.i(TAG, "battery state charging "+bm.isCharging());
            stop |= !bm.isCharging();
        }

        return !stop;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        // ensure notification channel on new OS versions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(new NotificationChannel(
                        NOTIFICATION_CHANNEL_DAEMON,
                        "BitcoinCOre daemon notification",
                        NotificationManager.IMPORTANCE_HIGH
                ));
            }
        }

        // prepare notification with empty status
        status_ = null;
        Notification n = createNotification();

        // bring this service to foreground
        startForeground(NOTIFICATION_ID_DAEMON, n);

        // read cmd options from SharedPrefs
        SharedPreferences prefs = getSharedPreferences(Common.PREFS_FILE, Context.MODE_PRIVATE);
        String params = prefs.getString("cmdline", "");
//        DataFile df = new DataFile(Common.getCmdlineFile(DaemonService.this));
//        String params = df.read();

        // start worker thread that will launch our process and collect it's output
        worker_ = new ProcessWorker(this, "libbitcoind.so", params, !params.contains("-help"));
        worker_.setCallback(new Runnable() {
            @Override
            public void run() {
                // mark as stopped, do this in worker thread
                // to make sure main thread could notice if it's actively polling the flag.
                started_.set(false);

                // signal to service thread that we're done
                childHandler_.sendMessage(childHandler_.obtainMessage(0, worker_.result()));
            }
        });
        worker_.start();

        // start status monitoring thread,
        // it will exit when 'started_' is off
        new Thread(new Runnable() {
            @Override
            public void run() {

                // prepare client to poll daemon status
                final ProcessWorker client = new ProcessWorker(
                        DaemonService.this.getApplicationContext(),
                        "libbitcoin-cli.so",
                        "-getinfo",
                        false);

                // set a callback that will executed in the child thread
                client.setCallback(new Runnable() {
                    @Override
                    public void run() {
                        // send status to service thread
                        childHandler_.sendMessage(childHandler_.obtainMessage(1, client.result()));
                    }
                });

                // poll the status while daemon is started
                while (started_.get()) {

                    // pause this poller, do it before calling so
                    // that the very first call happens after daemon gets
                    // some time to start
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException e) {}

                    if (isAutoStart()) {
                        if (!isStartAllowed()) {
                            Log.i(TAG, "stopping due to low power/network state");
                            worker_.interrupt();
                        }
                    }

                    // restart client to update the status
                    if (started_.get() && !client.isStarted()) {
                        client.start();
                    }
                }
            }
        }).start();

        // this is very important, as it tells OS to not
        // try to restart this service after 'crash'
        return START_NOT_STICKY;
    }

    private void onDone(String result) {

        Log.i(TAG,"stopped");

        // check if parent as killed and we have a detached child daemon now,
        // this is bad and we should terminate the child ASAP
/*        if (result.contains("Cannot obtain a lock on data directory")) {
            // get pid of orphaned bitcoid
            try {
                java.lang.Process p = new ProcessBuilder("ps").start();
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.contains("libbitcoind.so")) {

                    }
                }


            } catch (IOException e) {
                // do not loop/retry. just let
                // bg worker retry starting the daemon
            }
        }
*/
        // notify clients about status change
        notifyClients(MSG_STATUS, "Stopped");

        // notify clients about error
        if (result != null && !result.isEmpty())
            notifyClients(MSG_ERROR, result);

        // tell OS that this Service no longer needs to live
        // if all clients unbind.
        stopSelf();

        // don't show notification any longer
        stopForeground(true);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(NOTIFICATION_ID_DAEMON);
        }
    }

    private void updateStatus(String statusJson) {
        if (statusJson != null && statusJson.startsWith("error code: -28"))
            statusJson = null;

        if (statusJson == null) {
            status_ = "Starting...";
        } else if (statusJson.startsWith("error")) {
            status_ = statusJson;
        } else if (statusJson.startsWith("Stopping")) {
            status_ = statusJson;
        } else {
            status_ = "Error: bad status json";
            try {
                JSONObject j = new JSONObject(statusJson);
                int blocks = j.getInt("blocks");
                int headers = j.getInt("headers");
                double progress = j.getDouble("verificationprogress");
                if (progress < 0.99) {
                    status_ = "IBD " + String.format("%2.1f", progress * 100) + "% block " + blocks + " head " + headers;
                } else {
                    status_ = "Block " + blocks + " head " + headers;
                }
            } catch (JSONException e) {
            }
        }
    }

    private String getStatus() {
        if (started_.get()) {
            return status_ != null ? status_ : "Starting...";
        } else {
            return "Stopped";
        }
    }

    private void onStatus(String result) {
        // update
        updateStatus(result.trim());

        // update notification
        Notification n = createNotification();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID_DAEMON, n);
        }

        // notify clients about status change
        notifyClients(MSG_STATUS, getStatus());
    }

    private void onMsgStart(final Messenger replyTo) {
        // subscribe this client
        if (replyTo != null)
            clients_.add(new WeakReference<>(replyTo));

        if (isAutoStart() && !isStartAllowed()) {
            Log.e(TAG, "starting disallowed");
            send(replyTo, MSG_START, "Not allowed, turn auto-start off or attach charger and wifi");
            return;
        }

        // make this idempotent
        if (!started_.compareAndSet(false, true)) {
            Log.e(TAG, "already started");
            send(replyTo, MSG_START, "Already started");
            return;
        }

        // make sure OS treats this service as 'started' and keeps it running
        // even after no clients are bound.
        Intent intent = new Intent(this, DaemonService.class);
        ContextCompat.startForegroundService(this, intent);

        // reply to client
        send(replyTo, MSG_START, "Starting");
        notifyClients(MSG_STATUS, "Starting...");
    }

    private void notifyClients(int what, String status) {
        ListIterator<WeakReference<Messenger>> i = clients_.listIterator();
        while (i.hasNext()) {
            WeakReference<Messenger> r = i.next();
            Messenger m = r.get();

            // if messenger owner was no longer used,
            // VM could free the messenger and our weak ref turns to null,
            // otherwise (with hard refs) we'd hold all clients and
            // produce a memory leak
            if (m != null) {
                if (!send(m, what, status))
                    r.clear();
            }

            // drop dead client
            if (r.get() == null) {
                i.remove();
            }
        }
    }

    private void sendSigInt() {
        if (worker_ != null)
            worker_.interrupt();
    }

    private void onMsgStop(Messenger replyTo) {
        if (!started_.get()) {
            send(replyTo, MSG_STOP, "Not started");
            return;
        }

        // send ourselves a signal to force bitcoind to shut down
        Log.i(TAG, "sending SIGINT");
        sendSigInt();

        // schedule next auto-start for later
        if (isAutoStart()) {
            SharedPreferences.Editor e = getSharedPreferences(Common.PREFS_FILE, Context.MODE_PRIVATE).edit();
            e.putLong("nextStartTime", System.currentTimeMillis() + PAUSE_INTERVAL);
            e.apply();
        }

        // update status and notify all clients
        onStatus("Stopping...");

        // notify we're stopping
        if (isAutoStart()) {
            send(replyTo, MSG_STOP, "Stopping, will not auto-start for 30 minutes");
        } else {
            send(replyTo, MSG_STOP, "Stopping...");
        }
    }

    private void onMsgStatus(Messenger replyTo) {
        clients_.add(new WeakReference<>(replyTo));
        send(replyTo, MSG_STATUS, getStatus());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // if this is called by OS while we're started,
        // try to terminate the child gracefully and
        // then make sure we kill our process
        if (started_.get()) {
            try {

                // ask child to terminate
                sendSigInt();

                // give it some time to flush data to disk
                for (int i = 0; i < 10; i++) {
                    if (!started_.get())
                        break;
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
                // ignore
            }
            Log.i(TAG, "destroyed");
        }
    }
}
