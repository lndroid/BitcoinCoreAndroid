package org.lndroid.bitcoincore;

import android.content.Context;

import java.io.File;

public class Common {

    public static final String PREFS_FILE = "org.lndroid.bitcoincore.PREFS";
    public static final String DEFAULT_CMDLINE = "-prune=600 -maxmempool=100 -dbcache=50 -par=1 -persistmempool=0 -debug=0";

    public static String getBitcoinDir(Context ctx) {
        return ctx.getFilesDir() + File.separator + ".bitcoin";
    }

    public static String getConfFile(Context ctx) {
        return getBitcoinDir(ctx) + File.separator + "bitcoin.conf";
    }

    public static boolean ensurePath(String path) {
        File dir = new File(path);
        if (dir.exists()) {
            return true;
        } else {
            return dir.mkdirs();
        }

    }
}
