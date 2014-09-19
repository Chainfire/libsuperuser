package eu.chainfire.libsuperuser_example;

public class HideOverlaysReceiver extends eu.chainfire.libsuperuser.HideOverlaysReceiver {
    @Override
    public void onHideOverlays(boolean hide) {
        if (hide) {
            // hide overlays, if any
        } else {
            // show previously hidden overlays, if any
        }
    }
}
