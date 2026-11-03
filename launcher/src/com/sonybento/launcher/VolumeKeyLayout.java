package com.sonybento.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Publishes res/raw/volume_keys.kcm to the input manager; the layout is declared in the manifest meta-data. */
public class VolumeKeyLayout extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
    }
}
