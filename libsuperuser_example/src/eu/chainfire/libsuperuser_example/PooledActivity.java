/*
 * Copyright (C) 2012-2019 Jorrit "Chainfire" Jongma
 * Copyright (C) 2013 Kevin Cernekee
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.chainfire.libsuperuser_example;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.util.ArrayList;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import eu.chainfire.libsuperuser.Shell;

public class PooledActivity extends Activity {
    private TextView outputText;
    private ProgressDialog dialog;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        outputText = (TextView)findViewById(R.id.text);

        dialog = new ProgressDialog(this);
        dialog.setTitle("Some title");
        dialog.setMessage("Doing something interesting ...");
        dialog.setIndeterminate(true);
        dialog.setCancelable(false);

        // mode switch button
        Button button = (Button)findViewById(R.id.switch_button);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                (new AlertDialog.Builder(PooledActivity.this)).
                        setItems(new CharSequence[] {
                                getString(R.string.mode_legacy),
                                getString(R.string.mode_interactive),
                                getString(R.string.mode_pooled) + " " + getString(R.string.mode_current)
                        }, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (which == 0) {
                                    startActivity(new Intent(PooledActivity.this, MainActivity.class));
                                    finish();
                                } else if (which == 1) {
                                    startActivity(new Intent(PooledActivity.this, InteractiveActivity.class));
                                    finish();
                                }
                            }
                        }).
                        show();
            }
        });

        // refresh button
        ((Button)findViewById(R.id.refresh_button)).
        setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runRootCommands();
            }
        });

        runRootCommands();
    }

    public static class RootCommands extends Thread {
        private final WeakReference<PooledActivity> pooledActivityRef;

        @SuppressWarnings("Convert2Diamond")
        RootCommands(PooledActivity pooledActivity) {
            pooledActivityRef = new WeakReference<PooledActivity>(pooledActivity);
        }

        private void showDialog(final boolean show) {
            final PooledActivity activity = pooledActivityRef.get();
            if (activity != null) {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (show) {
                            activity.dialog.show();
                        } else {
                            activity.dialog.hide();
                        }
                    }
                });
            }
        }

        private void addLine(final String line) {
            // this is not particularly efficient, just for demo purposes
            final PooledActivity activity = pooledActivityRef.get();
            if (activity != null) {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        activity.outputText.append(line + "\n");
                    }
                });
            }
        }

        @SuppressWarnings("Convert2Diamond")
        @Override
        public void run() {
            showDialog(true);
            try {
                // this is true by default, but we set it to false specifically in MainActivity
                Shell.setRedirectDeprecated(true);

                // Shell.SU.XXX commands are automatically rerouted to use pool shells
                // you don't need to call them, using Shell.Pool.XXX below would throw an
                // exception if SU wasn't available
                boolean suAvailable = Shell.SU.available();
                String suVersion = Shell.SU.version(false);
                String suVersionInternal = Shell.SU.version(false);

                addLine("Root? " + (suAvailable ? "Yes" : "No"));
                addLine("Version: " + (suVersion == null ? "N/A" : suVersion));
                addLine("Version (internal): " + (suVersionInternal == null ? "N/A" : suVersionInternal));
                addLine("");

                try {
                    ArrayList<String> STDOUT = new ArrayList<String>();
                    ArrayList<String> STDERR = new ArrayList<String>();

                    // use any shell available from the pool
                    Shell.Pool.SU.run("id", STDOUT, STDERR, true);
                    addLine("id:");
                    for (String line : STDOUT) {
                        addLine("(stdout) " + line);
                    }
                    for (String line : STDERR) {
                        addLine("(stdout) " + line);
                    }
                    addLine("");

                    Shell.Pool.SU.run("ls -l /init", STDOUT, STDERR, true);
                    addLine("ls -l /init:");
                    for (String line : STDOUT) {
                        addLine("(stdout) " + line);
                    }
                    for (String line : STDERR) {
                        addLine("(stdout) " + line);
                    }
                    addLine("");

                    // get a shell from the pool and keep it to ourselves for a bit
                    Shell.Threaded shell = Shell.Pool.SU.get();
                    try {
                        for (int i = 0; i < 5; i++) {
                            shell.run("ls -l /proc/self/exe", STDOUT, STDERR, true);
                            addLine("ls -l /proc/self/exe:");
                            for (String line : STDOUT) {
                                addLine("(stdout) " + line);
                            }
                            for (String line : STDERR) {
                                addLine("(stdout) " + line);
                            }
                            shell.run("cat /init", new Shell.OnSyncCommandInputStreamListener() {
                                @Override
                                public void onInputStream(@NonNull InputStream inputStream) {
                                    try {
                                        MessageDigest md = MessageDigest.getInstance("MD5");

                                        byte[] buf = new byte[16384];
                                        int r;
                                        while ((r = inputStream.read(buf)) >= 0) {
                                            if (r > 0) {
                                                md.update(buf, 0, r);
                                            }
                                        }

                                        byte[] hash = md.digest();
                                        StringBuilder sb = new StringBuilder(2 * hash.length);
                                        for (byte b : hash){
                                            sb.append(String.format("%02x", b&0xff));
                                        }
                                        addLine("(inputstream) MD5 of /init: " + sb.toString());
                                    } catch (IOException e) {
                                        // shell is closed during read
                                        addLine("(error) " + e.getMessage());
                                    } catch (Exception e) {
                                        // this really shouldn't happen
                                        addLine("(unexpected error) " + e.getMessage());
                                        e.printStackTrace();
                                    }
                                }

                                @Override
                                public void onSTDERR(@NonNull String line) {
                                    addLine("(stderr) " + line);
                                }
                            });
                            addLine("");

                            try {
                                // keep our dialog open for a bit
                                Thread.sleep(1000);
                            } catch (InterruptedException e) {
                                // no action
                            }
                        }
                    } finally {
                        // return the shell to the pool
                        shell.close();
                    }
                } catch (Shell.ShellDiedException e) {
                    addLine("(error) " + e.getMessage());
                }
            } finally {
                showDialog(false);
            }
        }
    }

    @SuppressWarnings("FieldCanBeLocal")
    private RootCommands rootCommands;
    private void runRootCommands() {
        outputText.setText(null);
        rootCommands = new RootCommands(this);
        rootCommands.start();
    }
}
