// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.realtime;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import java.util.List;

/** Returns the devices Android would currently select for this player's game audio. */
public final class HrtfAudioRouteProbe {
    private HrtfAudioRouteProbe() {}

    public static int[] activeGameRouteDeviceTypes(Context context) {
        if (context == null || Build.VERSION.SDK_INT < 33) return new int[0];
        try {
            AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (manager == null) return new int[0];
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            List<AudioDeviceInfo> devices = manager.getAudioDevicesForAttributes(attributes);
            if (devices == null || devices.isEmpty()) return new int[0];
            int sinkCount = 0;
            for (AudioDeviceInfo device : devices) {
                if (device != null && device.isSink()) sinkCount++;
            }
            int[] types = new int[sinkCount];
            int index = 0;
            for (AudioDeviceInfo device : devices) {
                if (device != null && device.isSink()) types[index++] = device.getType();
            }
            return types;
        } catch (RuntimeException | LinkageError error) {
            return new int[0];
        }
    }
}
