/*
 * Copyright (C) 2012 Jorrit "Chainfire" Jongma
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

import eu.chainfire.libsuperuser.Shell;

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
            public void onCommandResult(int commandCode, int exitCode, List<String> output) {
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
            public void onLine(String line) {
                appendLineToOutput(line);
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
            public void onLine(String line) {
                appendLineToOutput(line);
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
                    open(new Shell.OnCommandResultListener() {

                        // Callback to report whether the shell was successfully started up 
                        @Override
                        public void onCommandResult(int commandCode, int exitCode, List<String> output) {
                            // note: this will FC if you rotate the phone while the dialog is up
                            dialog.dismiss();

                            if (exitCode != Shell.OnCommandResultListener.SHELL_RUNNING) {
                                reportError("Error opening root shell: exitCode " + exitCode);
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
        button.setText(R.string.disable_interactive_mode);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(v.getContext(), MainActivity.class));
                finish();
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
