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

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import eu.chainfire.libsuperuser.Shell;

import android.content.DialogInterface;
import android.os.Bundle;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class InteractiveActivity extends Activity {

    TextView outputText;

    private static Shell.Interactive rootSession;

    private void updateResultStatus(boolean suAvailable, List<String> suResult) {
        StringBuilder sb = (new StringBuilder()).
                append("Root? ").append(suAvailable ? "Yes" : "No").append((char)10).
                append((char)10);
        if (suResult != null) {
            for (String line : suResult) {
                sb.append(line).append((char)10);
            }
        }
        outputText.setText(sb.toString());
    }

    private void appendLineToOutput(String line) {
        StringBuilder sb = (new StringBuilder()).
                append(line).
                append((char)10);
        outputText.append(sb.toString());
    }

    private void reportError(String error) {
        List<String> errorInfo = new ArrayList<String>();
        errorInfo.add(error);
        updateResultStatus(false, errorInfo);
        rootSession = null;
    }

    private void sendRootCommand() {
        rootSession.addCommand(new String[] { "id", "date", "ls -l /" }, 0,
                new Shell.OnCommandResultListener() {
            public void onCommandResult(int commandCode, int exitCode, @NonNull List<String> output) {
                if (exitCode < 0) {
                    reportError("Error executing commands: exitCode " + exitCode);
                } else {
                    updateResultStatus(true, output);

                    appendLineToOutput("----------");
                    appendLineToOutput("ls -l /");
                }
            }
        });

        rootSession.addCommand(new String[] {"ls -l /"}, 1, new Shell.OnCommandLineListener() {
            @Override
            public void onCommandResult(int commandCode, int exitCode) {
                if (exitCode < 0) {
                    reportError("Error executing commands: exitCode " + exitCode);
                } else {
                    appendLineToOutput("----------");
                    appendLineToOutput("ls -l /sdcard");
                }
            }

            @Override
            public void onSTDOUT(@NonNull String line) {
                appendLineToOutput(line);
            }

            @Override
            public void onSTDERR(@NonNull String line) {
                appendLineToOutput("(stderr) " + line);
            }
        });

        rootSession.addCommand(new String[] {"ls -l /sdcard"}, 2, new Shell.OnCommandLineListener() {
            @Override
            public void onCommandResult(int commandCode, int exitCode) {
                if (exitCode < 0) {
                    reportError("Error executing commands: exitCode " + exitCode);
                }
            }

            @Override
            public void onSTDOUT(@NonNull String line) {
                appendLineToOutput(line);
            }

            @Override
            public void onSTDERR(@NonNull String line) {
                appendLineToOutput("(stderr) " + line);
            }
        });
    }

    private void openRootShell() {
        if (rootSession != null) {
            sendRootCommand();
        } else {
            // We're creating a progress dialog here because we want the user to wait.
            // If in your app your user can just continue on with clicking other things,
            // don't do the dialog thing.
            final ProgressDialog dialog = new ProgressDialog(this);
            dialog.setTitle("Please wait");
            dialog.setMessage("Requesting root privilege...");
            dialog.setIndeterminate(true);
            dialog.setCancelable(false);
            dialog.show();

            // start the shell in the background and keep it alive as long as the app is running
            rootSession = new Shell.Builder().
                    useSU().
                    setWantSTDERR(true).
                    setWatchdogTimeout(5).
                    setMinimalLogging(true).
                    open(new Shell.OnShellOpenResultListener() {
                        // Callback to report whether the shell was successfully started up
                        @Override
                        public void onOpenResult(boolean success, int reason) {
                            // note: this will FC if you rotate the phone while the dialog is up
                            dialog.dismiss();

                            if (!success) {
                                reportError("Error opening root shell: exitCode " + reason);
                            } else {
                                // Shell is up: send our first request
                                sendRootCommand();
                            }
                        }
                    });
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        outputText = (TextView)findViewById(R.id.text);

        // mode switch button
        Button button = (Button)findViewById(R.id.switch_button);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                (new AlertDialog.Builder(InteractiveActivity.this)).
                        setItems(new CharSequence[] {
                                getString(R.string.mode_legacy),
                                getString(R.string.mode_interactive) + " " + getString(R.string.mode_current),
                                getString(R.string.mode_pooled)
                        }, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (which == 0) {
                                    startActivity(new Intent(InteractiveActivity.this, MainActivity.class));
                                    finish();
                                } else if (which == 2) {
                                    startActivity(new Intent(InteractiveActivity.this, PooledActivity.class));
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
                openRootShell();
            }
        });

        openRootShell();
    }
}
