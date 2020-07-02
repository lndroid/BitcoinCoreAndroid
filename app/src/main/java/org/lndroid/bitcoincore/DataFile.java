package org.lndroid.bitcoincore;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class DataFile {

    private String path_ = "";

    public DataFile(String path) {
        path_ = path;
    }

    public String read() {
        try {
            FileInputStream s = new FileInputStream(path_);
            StringBuilder resultStringBuilder = new StringBuilder();
            try (BufferedReader br
                         = new BufferedReader(new InputStreamReader(s))) {
                String line;
                while ((line = br.readLine()) != null) {
                    resultStringBuilder.append(line).append("\n");
                }
            }

            return resultStringBuilder.toString();
        } catch (Exception e) {
            // ignore
        }

        return "";
    }

    public boolean write(String content) {
        String temp = path_ + "~";
        try {
            File file = new File(temp);
            FileOutputStream f = new FileOutputStream(file);
            f.write(content.getBytes());
            f.close();
            File dest = new File(path_);
            return file.renameTo(dest);
        } catch (IOException e) {
            Log.e("DataFile", "Failed to write to "+temp+": "+e.getLocalizedMessage());
            return false;
        }
    }

}
