package org.lndroid.bitcoincore;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class ProcessWorker {

    private static final String TAG = "ProcessWorker";

    private boolean stdoutDevnull_;
    private String dir_;
    private String libDir_;
    private List<String> command_;
    private Executor callbackExecutor_;
    private Runnable callback_;
    private AtomicReference<Thread> thread_ = new AtomicReference<>();
    private AtomicInteger pid_ = new AtomicInteger(-1);
    private AtomicReference<String> result_ = new AtomicReference<>();

    public ProcessWorker(Context ctx, String module, String params, boolean stdoutDevnull) {
        stdoutDevnull_ = stdoutDevnull;

        dir_ = Common.getBitcoinDir(ctx);
        libDir_ = ctx.getApplicationInfo().nativeLibraryDir;
        command_ = new ArrayList<>();
        command_.add(libDir_+File.separator+module);
        command_.add("-datadir="+dir_);

        boolean quote = false;
        StringBuilder param = new StringBuilder();
        for(int i = 0; i < params.length(); i++) {
            char c = params.charAt(i);
            if (c == '"' || c == '\'') {
                quote = !quote;
            } else {
                if (Character.isSpaceChar(c)) {
                    if (param.length() > 0 && !param.toString().equals("-daemon")) {
                        command_.add(param.toString());
                    }
                    // reset
                    param = new StringBuilder();
                } else {
                    param.append(c);
                }
            }
        }
        if (param.length() > 0 && !param.toString().equals("-daemon"))
            command_.add(param.toString());
    }

    public void setCallback(Runnable callback) {
        callback_ = callback;
    }

    public void setCallbackExecutor(Executor executor) {
        callbackExecutor_ = executor;
    }

    // https://stackoverflow.com/a/33171840
    private static synchronized int getPid(Process p) {
        int pid = -1;

        try {
            if (p.getClass().getName().equals("java.lang.UNIXProcess")) {
                Field f = p.getClass().getDeclaredField("pid");
                f.setAccessible(true);
                pid = f.getInt(p);
                f.setAccessible(false);
            }
        } catch (Exception e) {
            pid = -1;
        }
        return pid;
    }

    public void start() {
        thread_.set(new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ProcessBuilder b = new ProcessBuilder(command_)
                            .directory(new File(dir_));
                    b.environment().put("LD_LIBRARY_PATH", libDir_);
                    Process p = b.start();
                    pid_.set(getPid(p));
                    Log.i(TAG, "client process started: "+pid_.get());

                    InputStream stdout = p.getInputStream();
                    InputStream stderr = p.getErrorStream();

                    StringBuilder out = new StringBuilder();
                    StringBuilder err = new StringBuilder();

                    byte[] buf = new byte[256];
                    boolean alive = true;
                    while(alive || stdout.available() > 0 || stderr.available() > 0) {
                        if (stdout.available() > 0) {
                            final int max = Math.min(stdout.available(), buf.length);
                            final int r = stdout.read(buf, 0, max);
                            if (!stdoutDevnull_)
                                out.append(new String(buf, 0, r, "UTF-8"));
                        }
                        if (stderr.available() > 0) {
                            final int max = Math.min(stderr.available(), buf.length);
                            final int r = stderr.read(buf, 0, max);
                            err.append(new String(buf, 0, r, "UTF-8"));
                        }

                        if (alive) {
                            try {
                                p.exitValue();
                                alive = false;

                                // clear pid ASAP so that clients couldn't send signals to
                                // our finished process
                                pid_.set(-1);

                            } catch (IllegalThreadStateException e) {}
                        }
                    }

                    if (p.exitValue() != 0)
                        result_.set(err.toString());
                    else
                        result_.set(out.toString());

                    Log.i(TAG, "process "+command_.get(0)+" done "+p.exitValue());
                    Log.i(TAG, "result "+result_.get());

                    thread_.set(null);

                } catch (IOException e) {
                    Log.e(TAG, "Failed to run "+command_.get(0)+": "+e.getLocalizedMessage());
                    result_.set(e.getLocalizedMessage());
                }

                if (callback_ != null) {
                    if (callbackExecutor_ != null)
                        callbackExecutor_.execute(callback_);
                    else
                        callback_.run();
                }
            }
        }));
        thread_.get().start();
    }

    public boolean isStarted() {
        return thread_.get() != null;
    }

    public String result() {
        return result_.get();
    }

    public void interrupt() {
        if (pid_.get() >= 0)
            android.os.Process.sendSignal(pid_.get(), 2); // SIGINT
    }
}
