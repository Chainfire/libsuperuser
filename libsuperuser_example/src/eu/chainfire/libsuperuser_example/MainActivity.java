/*
 * Copyright (C) 2012-2019 Jorrit "Chainfire" Jongma
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

import java.util.List;

import androidx.appcompat.app.AlertDialog;
import eu.chainfire.libsuperuser.Shell;

import android.content.DialogInterface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;
import android.widget.Button;

public class MainActivity extends Activity {
    private static class Startup extends AsyncTask<Void, Void, Void> {
        private ProgressDialog dialog = null;
        private Context context = null;
        private boolean suAvailable = false;
        private String suVersion = null;
        private String suVersionInternal = null;
        private List<String> suResult = null;

        public Startup setContext(Context context) {
            this.context = context;
            return this;
        }

        @Override
        protected void onPreExecute() {
            // We're creating a progress dialog here because we want the user to wait.
            // If in your app your user can just continue on with clicking other things,
            // don't do the dialog thing.

            // make sure we're using the legacy methods for this legacy activity
            Shell.setRedirectDeprecated(false);

            dialog = new ProgressDialog(context);
            dialog.setTitle("Some title");
            dialog.setMessage("Doing something interesting ...");
            dialog.setIndeterminate(true);
            dialog.setCancelable(false);
            dialog.show();
        }

        @Override
        protected Void doInBackground(Void... params) {
            // Let's do some SU stuff
            suAvailable = Shell.SU.available();
            if (suAvailable) {
                suVersion = Shell.SU.version(false);
                suVersionInternal = Shell.SU.version(true);
                suResult = Shell.SU.run(new String[] {
                        "id",
                        "ls -l /"
                });
            }

            // This is just so you see we had a progress dialog, 
            // don't do this in production code
            try { Thread.sleep(5000); } catch(Exception e) { }

            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            dialog.dismiss();

            // output
            StringBuilder sb = (new StringBuilder()).
                    append("Root? ").append(suAvailable ? "Yes" : "No").append((char)10).
                    append("Version: ").append(suVersion == null ? "N/A" : suVersion).append((char)10).
                    append("Version (internal): ").append(suVersionInternal == null ? "N/A" : suVersionInternal).append((char)10).
                    append((char)10);
            if (suResult != null) {
                for (String line : suResult) {
                    sb.append(line).append((char)10);
                }
            }
            ((TextView)((Activity)context).findViewById(R.id.text)).setText(sb.toString());

            setContext(null);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // mode switch button
        Button button = (Button)findViewById(R.id.switch_button);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                (new AlertDialog.Builder(MainActivity.this)).
                        setTitle("Some title").
                        setItems(new CharSequence[] {
                                getString(R.string.mode_legacy) + " " + getString(R.string.mode_current),
                                getString(R.string.mode_interactive),
                                getString(R.string.mode_pooled)
                        }, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (which == 1) {
                                    startActivity(new Intent(MainActivity.this, InteractiveActivity.class));
                                    finish();
                                } else if (which == 2) {
                                    startActivity(new Intent(MainActivity.this, PooledActivity.class));
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
                (new Startup()).setContext(v.getContext()).execute();
            }
        });

        // Let's do some background stuff
        (new Startup()).setContext(this).execute();
    }
}
