package eu.chainfire.libsuperuser_example;

import android.content.Context;
import android.content.Intent;

public class HideOverlaysReceiver extends eu.chainfire.libsuperuser.HideOverlaysReceiver {
    @Override
    public void onHideOverlays(Context context, Intent intent, boolean hide) {
        if (hide) {
            // hide overlays, if any
        } else {
            // show previously hidden overlays, if any
        }
    }
}
