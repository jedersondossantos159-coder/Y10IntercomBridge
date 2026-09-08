package com.example.y10bridge;

import android.Manifest;
import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.Build;
import android.os.IBinder;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class BridgeService extends Service {

    private static final String CHANNEL_ID = "y10_bridge";
    private static final int NOTIFICATION_ID = 101;

    private AudioManager audioManager;
    private AudioRecord recorder;
    private AudioTrack player;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(
                NOTIFICATION_ID,
                buildNotification("Intercom ativo")
        );

        if (!running.get()) {
            new Thread(this::startBridge, "Y10Bridge").start();
        }

        return START_STICKY;
    }

    private void startBridge() {
        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);

            if (Build.VERSION.SDK_INT >= 31) {

                if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    stopSelf();
                    return;
                }

                List<AudioDeviceInfo> devices =
                        audioManager.getAvailableCommunicationDevices();

                for (AudioDeviceInfo device : devices) {
                    if (device.getType() ==
                            AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {

                        audioManager.setCommunicationDevice(device);
                        break;
                    }
                }

            } else {
                audioManager.startBluetoothSco();
                audioManager.setBluetoothScoOn(true);

                try {
                    Thread.sleep(1200);
                } catch (InterruptedException ignored) {
                }
            }

            int sampleRate = 16000;
            int encoding = AudioFormat.ENCODING_PCM_16BIT;
            int inputChannel = AudioFormat.CHANNEL_IN_MONO;
            int outputChannel = AudioFormat.CHANNEL_OUT_MONO;

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

            int bufferSize =
                    Math.max(4096, Math.max(inputMin, outputMin) * 2);

            recorder = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    inputChannel,
                    encoding,
                    bufferSize
            );

            AudioAttributes attributes =
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(
                                    AudioAttributes.CONTENT_TYPE_SPEECH
                            )
                            .build();

            AudioFormat format =
                    new AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(sampleRate)
                            .setChannelMask(outputChannel)
                            .build();

            player = new AudioTrack(
                    attributes,
                    format,
                    bufferSize,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
            );

            recorder.startRecording();
            player.play();

            running.set(true);

            byte[] buffer = new byte[bufferSize];

            while (running.get()) {
                int bytesRead =
                        recorder.read(buffer, 0, buffer.length);

                if (bytesRead > 0) {
                    player.write(buffer, 0, bytesRead);
                }
            }

        } catch (Throwable error) {
            error.printStackTrace();

        } finally {
            cleanup();
            stopSelf();
        }
    }

    private void cleanup() {
        running.set(false);

        try {
            if (recorder != null) recorder.stop();
        } catch (Exception ignored) {
        }

        try {
            if (player != null) player.stop();
        } catch (Exception ignored) {
        }

        try {
            if (recorder != null) recorder.release();
        } catch (Exception ignored) {
        }

        try {
            if (player != null) player.release();
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

            audioManager.setMode(AudioManager.MODE_NORMAL);

        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    private Notification buildNotification(String text) {

        Intent openApp =
                new Intent(this, MainActivity.class);

        PendingIntent pendingIntent =
                PendingIntent.getActivity(
                        this,
                        0,
                        openApp,
                        PendingIntent.FLAG_IMMUTABLE
                                | PendingIntent.FLAG_UPDATE_CURRENT
                );

        if (Build.VERSION.SDK_INT >= 26) {
            return new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Y10 Intercom Bridge")
                    .setContentText(text)
                    .setSmallIcon(
                            android.R.drawable.stat_sys_headset
                    )
                    .setOngoing(true)
                    .setContentIntent(pendingIntent)
                    .build();
        }

        return new Notification.Builder(this)
                .setContentTitle("Y10 Intercom Bridge")
                .setContentText(text)
                .setSmallIcon(
                        android.R.drawable.stat_sys_headset
                )
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {

            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            "Y10 Intercom",
                            NotificationManager.IMPORTANCE_LOW
                    );

            channel.setDescription(
                    "Mantém o intercom ativo com a tela bloqueada."
            );

            NotificationManager manager =
                    getSystemService(NotificationManager.class);

            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
