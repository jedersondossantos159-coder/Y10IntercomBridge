package com.example.y10bridge;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
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
    private PowerManager.WakeLock wakeLock;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<AudioDeviceInfo> communicationDevices = new ArrayList<>();
    private final List<AudioDeviceInfo> outputDevices = new ArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Y10 Intercom V2",
                    NotificationManager.IMPORTANCE_LOW
            );

            getSystemService(NotificationManager.class)
                    .createNotificationChannel(channel);
        }

        PowerManager powerManager =
                (PowerManager) getSystemService(POWER_SERVICE);

        wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Y10Bridge:Audio"
        );
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        startForeground(
                NOTIFICATION_ID,
                buildNotification("Intercom ativo")
        );

        if (!wakeLock.isHeld()) {
            wakeLock.acquire();
        }

        final int inputIndex = intent.getIntExtra("input", 0);
        final int outputIndex = intent.getIntExtra("output", -1);
        final int mode = intent.getIntExtra("mode", 0);
        final int gain = intent.getIntExtra("gain", 100);
        final int vox = intent.getIntExtra("vox", 20);

        running.set(false);
        cleanupAudio();

        new Thread(() ->
                runBridge(
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

        communicationDevices.clear();
        outputDevices.clear();

        if (Build.VERSION.SDK_INT >= 31) {
            try {
                for (AudioDeviceInfo device :
                        audioManager.getAvailableCommunicationDevices()) {

                    int type = device.getType();

                    if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                        communicationDevices.add(device);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        for (AudioDeviceInfo device :
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {

            int type = device.getType();

            if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                outputDevices.add(device);
            }
        }
    }

    private void runBridge(
            int inputIndex,
            int outputIndex,
            int mode,
            int gain,
            int vox
    ) {

        try {

            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);

            refreshDevices();

            if (Build.VERSION.SDK_INT >= 31) {

                if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    return;
                }

                if (!communicationDevices.isEmpty()) {

                    int safeInput = Math.max(
                            0,
                            Math.min(
                                    inputIndex,
                                    communicationDevices.size() - 1
                            )
                    );

                    audioManager.setCommunicationDevice(
                            communicationDevices.get(safeInput)
                    );
                }

            } else {

                audioManager.startBluetoothSco();
                audioManager.setBluetoothScoOn(true);

                Thread.sleep(1000);
            }

            final int sampleRate = 16000;
            final int encoding = AudioFormat.ENCODING_PCM_16BIT;
            final int inputChannel = AudioFormat.CHANNEL_IN_MONO;
            final int outputChannel = AudioFormat.CHANNEL_OUT_MONO;

            int inputMin = AudioRecord.getMinBufferSize(
                    sampleRate,
                    inputChannel,
                    encoding
            );

            int outputMin = AudioTrack.getMinBufferSize(
                    sampleRate,
                    outputChannel,
                    encoding
            );

            int bufferSize = Math.max(
                    4096,
                    Math.max(inputMin, outputMin) * 2
            );

            recorder = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    inputChannel,
                    encoding,
                    bufferSize
            );

            AudioAttributes audioAttributes =
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(
                                    AudioAttributes.CONTENT_TYPE_SPEECH
                            )
                            .build();

            AudioFormat audioFormat =
                    new AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(sampleRate)
                            .setChannelMask(outputChannel)
                            .build();

            player = new AudioTrack(
                    audioAttributes,
                    audioFormat,
                    bufferSize,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
            );

            if (outputIndex >= 0 &&
                    outputIndex < outputDevices.size()) {

                player.setPreferredDevice(
                        outputDevices.get(outputIndex)
                );
            }

            recorder.startRecording();
            player.play();

            running.set(true);

            short[] buffer =
                    new short[bufferSize / 2];

            int activeCommunicationDevice = Math.max(
                    0,
                    Math.min(
                            inputIndex,
                            Math.max(
                                    0,
                                    communicationDevices.size() - 1
                            )
                    )
            );

            long silenceSince =
                    System.currentTimeMillis();

            while (running.get()) {

                int samples = recorder.read(
                        buffer,
                        0,
                        buffer.length
                );

                if (samples <= 0) {
                    continue;
                }

                float digitalGain =
                        Math.max(0f, gain / 100f);

                long energy = 0;

                for (int i = 0; i < samples; i++) {

                    int value =
                            (int) (buffer[i] * digitalGain);

                    value = Math.max(
                            -32768,
                            Math.min(32767, value)
                    );

                    buffer[i] = (short) value;
                    energy += Math.abs(value);
                }

                int averageEnergy =
                        (int) (
                                energy /
                                Math.max(1, samples)
                        );

                if (mode == 2 &&
                        Build.VERSION.SDK_INT >= 31 &&
                        communicationDevices.size() >= 2) {

                    long now =
                            System.currentTimeMillis();

                    int threshold =
                            150 + (vox * 50);

                    if (averageEnergy > threshold) {

                        silenceSince = now;

                    } else if (
                            now - silenceSince > 650
                    ) {

                        activeCommunicationDevice =
                                (activeCommunicationDevice + 1) % 2;

                        audioManager.setCommunicationDevice(
                                communicationDevices.get(
                                        activeCommunicationDevice
                                )
                        );

                        silenceSince = now;
                    }
                }

                player.write(
                        buffer,
                        0,
                        samples,
                        AudioTrack.WRITE_BLOCKING
                );
            }

        } catch (Throwable error) {

            error.printStackTrace();

        } finally {

            cleanupAudio();
            stopSelf();
        }
    }

    private void cleanupAudio() {

        running.set(false);

        try {
            if (recorder != null) {
                recorder.stop();
            }
        } catch (Exception ignored) {
        }

        try {
            if (player != null) {
                player.stop();
            }
        } catch (Exception ignored) {
        }

        try {
            if (recorder != null) {
                recorder.release();
            }
        } catch (Exception ignored) {
        }

        try {
            if (player != null) {
                player.release();
            }
        } catch (Exception ignored) {
        }

        recorder = null;
        player = null;

        try {

            if (Build.VERSION.SDK_INT >= 31) {

                audioManager.clearCommunicationDevice();

            } else {

                audioManager.setBluetoothScoOn(false);
                audioManager.stopBluetoothSco();
            }

            audioManager.setMode(
                    AudioManager.MODE_NORMAL
            );

        } catch (Exception ignored) {
        }
    }

    private Notification buildNotification(String text) {

        PendingIntent pendingIntent =
                PendingIntent.getActivity(
                        this,
                        0,
                        new Intent(
                                this,
                                MainActivity.class
                        ),
                        PendingIntent.FLAG_IMMUTABLE |
                                PendingIntent.FLAG_UPDATE_CURRENT
                );

        Notification.Builder builder;

        if (Build.VERSION.SDK_INT >= 26) {
            builder =
                    new Notification.Builder(
                            this,
                            CHANNEL_ID
                    );
        } else {
            builder =
                    new Notification.Builder(this);
        }

        return builder
                .setContentTitle(
                        "Y10 Intercom Bridge V2"
                )
                .setContentText(text)
                .setSmallIcon(
                        android.R.drawable.stat_sys_headset
                )
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build();
    }

    @Override
    public void onDestroy() {

        cleanupAudio();

        try {
            if (wakeLock != null &&
                    wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
