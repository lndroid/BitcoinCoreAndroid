package org.lndroid.bitcoincore;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.Callable;

public class ClientService { // extends Service

/*    static {
        // loaded below in onStartCommand
        // System.loadLibrary("bitcoind");
    }

    public native String start(String params);

    private static final String TAG = "ClientService";

    // worker thread wrapper
    private ProcessWorker worker_;

    // clients that call 'start' or 'status' are added here
    private LinkedList<WeakReference<Messenger>> clients_ = new LinkedList<>();

    // used by worker thread to signal to main thread that it's done
    private Handler stopHandler_ = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(@NonNull Message msg) {
            Log.i(TAG,"daemon finished: "+msg.obj);

            String result = (String)msg.obj;
            onDone(result);
            return true;
        }
    });

    // binder to accept messages from clients
    private Messenger messenger_ = new Messenger(new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(@NonNull Message msg) {
            onMessage(msg.replyTo);
            return true;
        }
    }));

    // helper
    private boolean send(Messenger client, int what, String data) {
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

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        System.loadLibrary("bitcoinc");

        worker_ = new ProcessWorker(new Callable<String>() {
            @Override
            public String call() throws Exception {
                // start the bg thread
                // read cmd options from SharedPrefs
                DataFile df = new DataFile(Common.getCmdlineFile(DaemonService.this));
                String params = df.read();

                // ensure data dir
                final String dir = Common.getBitcoinDir(DaemonService.this);
                String result;
                if (!Common.ensurePath(dir)) {
                    result = "Failed to create bitcoin dir";
                    Log.e(TAG, result);
                } else {
                    String args = "'-datadir=" + dir + "' " + params;
                    Log.i(TAG, "starting daemon with " + args);
                    result = start(args);
                    Log.i(TAG, "daemon finished, result: " + result);
                }

                // mark ourselve as stopped so that parent thread could
                // notice and kill itself
                started_.set(false);

                // signal to main thread that we're done and service might terminate,
                // this is for a case where parent thread is not actively
                // watching the 'started' flag
                stopHandler_.sendMessage(stopHandler_.obtainMessage(0, result));

                return result;
            }
        });

        worker_.start();

        // this is very important, as it will mean OS won't
        // try to restart this service after 'crash' which is
        // the only way we stop the daemon process
        return START_NOT_STICKY;
    }

    private void onMessage(final Messenger replyTo) {
        clients_.add(new WeakReference<Messenger>(replyTo));

        // FIXME start yet another thread to process this request?
        // HEY! this means we can only execute one request at a time?
        // that's shit :( And so status requests performed regularly
        // cannot be implemented as it will interfere with requests
        // sent manually :(

        // reply to client
        send(replyTo, MSG_START, "Starting");
        notifyClients(MSG_STATUS, "Started");
    }

    private void onDone(String result) {
        notifyClients(MSG_STATUS, "Stopped");

        if (result != null && !result.isEmpty())
            notifyClients(MSG_ERROR, result);

        Log.i(TAG,"stopped");

        // not that child thread is done we need to kill our process
        // to reset it's state
        sendSigKill();

        // makes no sense while we have connected clients
//        DaemonService.this.stopSelf();
    }

    private void notifyClients(int what, String status) {
        ListIterator<WeakReference<Messenger>> i = clients_.listIterator();
        while (i.hasNext()) {
            WeakReference<Messenger> r = i.next();
            Messenger m = r.get();
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
        Process.sendSignal(Process.myPid(), 2); // SIGINT
    }

    private void sendSigKill() {
        Process.sendSignal(Process.myPid(), 9); // SIGKILL
    }

    private void onMsgStop(Messenger replyTo) {
        if (!started_.get()) {
            send(replyTo, MSG_STOP, "Not started");
            return;
        }

        // send ourselves a signal to force bitcoind to shut down
        Log.i(TAG, "sending SIGINT");
        sendSigInt();

        // notify we're stopping
        send(replyTo, MSG_STOP, "Stopping");
        notifyClients(MSG_STATUS, "Stopping");

//        onDone("Stopped");
    }

    private void onMsgStatus(Messenger replyTo) {
        clients_.add(new WeakReference<Messenger>(replyTo));
        send(replyTo, MSG_STATUS, started_.get() ? "Started" : "Stopped");
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

            // terminate this process
            sendSigKill();
        }
    }

 */
}
