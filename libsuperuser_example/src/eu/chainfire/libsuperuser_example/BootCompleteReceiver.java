/*
 * Copyright (C) 2012 Jorrit "Chainfire" Jongma
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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Example BootCompleteReceiver that starts MyIntentService
 * (please see MyIntentService.java) to handle the task 
 * in a background thread 
 */
public class BootCompleteReceiver extends BroadcastReceiver{
    @Override
    public void onReceive(Context context, Intent intent) {
        // What many beginners don't realize is that BroadcastReceivers like these
        // usually run in the application's main thread, and can thus generate
        // ANRs. This is increasingly likely with the BOOT_COMPLETED receiver, as
        // the system is likely very busy when this receiver is called.

        // In this example we are starting our MyIntentService to actually do the
        // work we want to happen, not only because "su" should specifically NEVER 
        // be called from a BroadcastReceiver, but also because you should be doing 
        // this even if you aren't calling "su". It's a good practise, and using 
        // IntentService is really easy.

        BackgroundIntentService.performAction(context, BackgroundIntentService.ACTION_BOOT_COMPLETE);
    }
}