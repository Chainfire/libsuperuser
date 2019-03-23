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

import androidx.annotation.NonNull;
import androidx.core.app.JobIntentService;
import eu.chainfire.libsuperuser.Application;
import eu.chainfire.libsuperuser.Shell;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * Example JobIntentService based class, to execute tasks in a background
 * thread. This would typically be used by BroadcastReceivers and other
 * fire-and-forget type calls.
 * 
 * For most background calls that would occur when the UI is visible, in
 * response to some user action and/or something you are waiting for,
 * you would typically use an AsyncTask instead of a service like this.
 * (See MainActivity.java for that example)
 * 
 * Note that the JobIntentService's onHandleWork call runs in a background
 * thread, while a normal service's calls would run in the main thread,
 * unless you put in the extra work. This is an important distinction
 * that is often overlooked by beginners.
 * 
 * This service starts running when needed, and stops running when the
 * task is done, automagically.
 * 
 * Please also see BootCompleteReceiver.java, and AndroidManifest.xml for
 * how and when this service is instantiated.
 * 
 * This code leaves some room for extension - if you really wanted to
 * respond only to a single event that always does the same, this code
 * could have been a lot shorter.
 *
 * NOTE: We transitioned from IntentService to JobIntentService to keep
 * working on Android 8+ Oreo. A boot complete receiver can no longer start
 * a direct service (such as IntentService), but it can schedule a job.
 *
 * The JobIntentService from the androidx package (note the x) takes care
 * of compatibility with pre-Oreo devices. Jobs have a time limit of
 * approximately 5 minutes.
 *
 * They also require the BIND_JOB_SERVICE permission (see the manifest) !
 */
public class BackgroundIntentService extends JobIntentService {
    // you could provide more options here, should you need them
    public static final String ACTION_BOOT_COMPLETE = "boot_complete";

    private static int JOB_ID = 100000;
    private static int getNextJobId() {
        synchronized (BackgroundIntentService.class) {
            JOB_ID += 1;
            return JOB_ID;
        }
    }

    public static void performAction(Context context, String action) {
        performAction(context, action, null);
    }

    public static void performAction(Context context, String action, Bundle extras) {
        // this is utility call to easy starting the service and performing a task
        // pass parameters in an bundle to be added to the intent as extras
        // See BootCompleteReceiver.java

        if ((context == null) || (action == null) || action.equals("")) return;

        Intent svc = new Intent(context, BackgroundIntentService.class);
        svc.setAction(action);
        if (extras != null) svc.putExtras(extras);
        enqueueWork(context, BackgroundIntentService.class, getNextJobId(), svc);
    }

    @Override
    protected void onHandleWork(@NonNull Intent intent) {
        String action = intent.getAction();
        if ((action == null) || (action.equals(""))) return;

        if (action.equals(ACTION_BOOT_COMPLETE)) {
            onBootComplete();
        }
        // you can define more options here... pass parameters through the "extra" values
    }

    protected void onBootComplete() {
        // We are running in a background thread here!

        // This would crash (when debugging) if it was called from the main thread: 
        Shell.SU.run("ls -l /");

        // Let's toast that we're done, using the work-arounds and utility function in
        // out Application class. Without those modifications there would be a very high 
        // chance of crashing the app in various Android versions. The modifications are
        // simple and easily ported to your own Application class, if you can't use the 
        // one from libsuperuser.
        Application.toast(this, "This toast will self-destruct in five seconds");
    }
}
