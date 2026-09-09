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

    private static final String CHANNEL_ID = "y10_bridge_dual";
    private static final int NOTIFICATION_ID = 303;

    private AudioManager audioManager;
    private AudioRecord recorder;
    private AudioTrack player;
    private PowerManager.WakeLock wakeLock;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final List<AudioDeviceInfo> bluetoothInputs = new ArrayList<>();
    private final List<AudioDeviceInfo> bluetoothOutputs = new ArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();

        audioManager =
                (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            "Y10 Intercom Dual",
                            NotificationManager.IMPORTANCE_LOW
                    );

            getSystemService(NotificationManager.class)
                    .createNotificationChannel(channel);
        }

        PowerManager powerManager =
                (PowerManager) getSystemService(POWER_SERVICE);

        wakeLock =
                powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "Y10Bridge:DualAudio"
                );
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {

        startForeground(
                NOTIFICATION_ID,
                buildNotification("Ponte para os dois Y10 ativa")
        );

        if (!wakeLock.isHeld()) {
            wakeLock.acquire();
        }

        final int gain =
                intent.getIntExtra("gain", 100);

        running.set(false);
        cleanupAudio();

        new Thread(
                () -> runDualBridge(gain),
                "Y10DualBridge"
        ).start();

        return START_STICKY;
    }

    private void scanBluetoothDevices() {

        bluetoothInputs.clear();
        bluetoothOutputs.clear();

        AudioDeviceInfo[] inputs =
                audioManager.getDevices(
                        AudioManager.GET_DEVICES_INPUTS
                );

        for (AudioDeviceInfo device : inputs) {

            int type = device.getType();

            if (
                    type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    ||
                    type == AudioDeviceInfo.TYPE_BLE_HEADSET
            ) {
                bluetoothInputs.add(device);
            }
        }

        AudioDeviceInfo[] outputs =
                audioManager.getDevices(
                        AudioManager.GET_DEVICES_OUTPUTS
                );

        for (AudioDeviceInfo device : outputs) {

            int type = device.getType();

            if (
                    type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    ||
                    type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    ||
                    type == AudioDeviceInfo.TYPE_BLE_HEADSET
                    ||
                    type == AudioDeviceInfo.TYPE_BLE_SPEAKER
            ) {
                bluetoothOutputs.add(device);
            }
        }
    }

    private void runDualBridge(int gain) {

        try {

            /*
             * PONTO PRINCIPAL:
             *
             * NÃO usamos MODE_IN_COMMUNICATION.
             * NÃO usamos setCommunicationDevice().
             * NÃO iniciamos SCO manualmente.
             *
             * Isso evita quebrar a saída dupla de mídia do HyperOS.
             */
            audioManager.setMode(
                    AudioManager.MODE_NORMAL
            );

            scanBluetoothDevices();

            final int sampleRate = 48000;
            final int encoding =
                    AudioFormat.ENCODING_PCM_16BIT;

            int inputMin =
                    AudioRecord.getMinBufferSize(
                            sampleRate,
                            AudioFormat.CHANNEL_IN_MONO,
                            encoding
                    );

            int outputMin =
                    AudioTrack.getMinBufferSize(
                            sampleRate,
                            AudioFormat.CHANNEL_OUT_STEREO,
                            encoding
                    );

            int bufferSize =
                    Math.max(
                            8192,
                            Math.max(
                                    inputMin,
                                    outputMin
                            ) * 2
                    );

            recorder =
                    new AudioRecord(
                            MediaRecorder.AudioSource.VOICE_RECOGNITION,
                            sampleRate,
                            AudioFormat.CHANNEL_IN_MONO,
                            encoding,
                            bufferSize
                    );

            /*
             * Se o Android expuser um microfone Bluetooth como INPUT,
             * tentamos usá-lo SEM mudar o telefone inteiro para modo chamada.
             *
             * Se não expuser, o sistema cai no microfone do Redmi.
             */
            if (!bluetoothInputs.isEmpty()) {

                try {
                    recorder.setPreferredDevice(
                            bluetoothInputs.get(0)
                    );
                } catch (Throwable ignored) {
                }
            }

            AudioAttributes attributes =
                    new AudioAttributes.Builder()
                            .setUsage(
                                    AudioAttributes.USAGE_MEDIA
                            )
                            .setContentType(
                                    AudioAttributes.CONTENT_TYPE_SPEECH
                            )
                            .build();

            AudioFormat format =
                    new AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(sampleRate)
                            .setChannelMask(
                                    AudioFormat.CHANNEL_OUT_STEREO
                            )
                            .build();

            player =
                    new AudioTrack(
                            attributes,
                            format,
                            bufferSize,
                            AudioTrack.MODE_STREAM,
                            AudioManager.AUDIO_SESSION_ID_GENERATE
                    );

            /*
             * MUITO IMPORTANTE:
             *
             * NÃO usamos setPreferredDevice() no AudioTrack.
             *
             * A saída fica "MEDIA / AUTOMÁTICA", permitindo ao HyperOS
             * continuar duplicando a mídia para os dois Y10,
             * exatamente como ocorre quando você toca música.
             */
            recorder.startRecording();
            player.play();

            running.set(true);

            short[] monoBuffer =
                    new short[bufferSize / 2];

            short[] stereoBuffer =
                    new short[bufferSize];

            while (running.get()) {

                int samples =
                        recorder.read(
                                monoBuffer,
                                0,
                                monoBuffer.length
                        );

                if (samples <= 0) {
                    continue;
                }

                float digitalGain =
                        Math.max(
                                0.0f,
                                gain / 100.0f
                        );

                int stereoSamples = 0;

                for (int i = 0; i < samples; i++) {

                    int value =
                            (int) (
                                    monoBuffer[i]
                                            * digitalGain
                            );

                    value =
                            Math.max(
                                    -32768,
                                    Math.min(
                                            32767,
                                            value
                                    )
                            );

                    short s =
                            (short) value;

                    /*
                     * Copia a voz igualmente para L e R.
                     * Depois o HyperOS decide para quais dispositivos
                     * de mídia Bluetooth mandar o áudio.
                     */
                    stereoBuffer[stereoSamples++] = s;
                    stereoBuffer[stereoSamples++] = s;
                }

                player.write(
                        stereoBuffer,
                        0,
                        stereoSamples,
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

        /*
         * Não limpamos CommunicationDevice porque esta versão
         * não assume controle da rota de chamada.
         */
        try {
            audioManager.setMode(
                    AudioManager.MODE_NORMAL
            );
        } catch (Exception ignored) {
        }
    }

    private Notification buildNotification(
            String text
    ) {

        PendingIntent pendingIntent =
                PendingIntent.getActivity(
                        this,
                        0,
                        new Intent(
                                this,
                                MainActivity.class
                        ),
                        PendingIntent.FLAG_IMMUTABLE
                                |
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
                        "Y10 Intercom Bridge"
                )
                .setContentText(text)
                .setSmallIcon(
                        android.R.drawable.stat_sys_headset
                )
                .setOngoing(true)
                .setContentIntent(
                        pendingIntent
                )
                .build();
    }

    @Override
    public void onDestroy() {

        cleanupAudio();

        try {

            if (
                    wakeLock != null
                            &&
                    wakeLock.isHeld()
            ) {
                wakeLock.release();
            }

        } catch (Exception ignored) {
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(
            Intent intent
    ) {
        return null;
    }
}
