package com.example.y10bridge;

import android.Manifest;
import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class BridgeService extends Service {

    private static final String CHANNEL_ID = "y10_bridge_v2";
    private static final int NOTIFICATION_ID = 202;

    private AudioManager audioManager;
    private AudioRecord recorder;
    private AudioTrack player;

    private final AtomicBoolean running =
            new AtomicBoolean(false);

    private final List<AudioDeviceInfo> commDevices =
            new ArrayList<>();

    private final List<AudioDeviceInfo> outputDevices =
            new ArrayList<>();

    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();

        audioManager =
                (AudioManager) getSystemService(
                        Context.AUDIO_SERVICE
                );

        createNotificationChannel();

        PowerManager pm =
                (PowerManager) getSystemService(
                        POWER_SERVICE
                );

        wakeLock =
                pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "Y10Bridge:Audio"
                );
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {

        int inputIndex =
                intent.getIntExtra(
                        "input",
                        0
                );

        int outputIndex =
                intent.getIntExtra(
                        "output",
                        -1
                );

        int mode =
                intent.getIntExtra(
                        "mode",
                        0
                );

        int gain =
                intent.getIntExtra(
                        "gain",
                        100
                );

        int vox =
                intent.getIntExtra(
                        "vox",
                        20
                );

        startForeground(
                NOTIFICATION_ID,
                buildNotification(
                        "Intercom ativo"
                )
        );

        if (!wakeLock.isHeld()) {
            wakeLock.acquire();
        }

        running.set(false);

        cleanupAudio();

        new Thread(
                () -> runBridge(
                        inputIndex,
                        outputIndex,
                        mode,
                        gain,
                        vox
                ),
                "Y10BridgeV2"
        ).start();

        return START_STICKY;
    }

    private void refreshDevices() {

        commDevices.clear();
        outputDevices.clear();

        if (Build.VERSION.SDK_INT >= 31) {

            try {

                for (
                        AudioDeviceInfo device :
                        audioManager
                                .getAvailableCommunicationDevices()
                ) {

                    if (
                            device.getType()
                                    ==
                            Audio
