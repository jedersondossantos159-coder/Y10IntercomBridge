package com.example.y10bridge;

import android.app.*;
import android.content.Intent;
import android.media.*;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import java.util.ArrayList;
import java.util.List;

public class BridgeService extends Service {

    private static final String CHANNEL_ID = "y10_bridge_v5";
    private static final int NOTIFICATION_ID = 205;

    private AudioManager audioManager;
    private AudioRecord recorder;
    private AudioTrack player;
    private PowerManager.WakeLock wakeLock;

    private volatile boolean running = false;
    private Thread audioThread;
    private Thread routeThread;

    private int gainPercent = 120;
    private boolean blockPhoneMic = true;
    private boolean autoAB = true;

    private final List<AudioDeviceInfo> communicationDevices = new ArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();

        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createNotificationChannel();

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Y10Bridge::V5");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            gainPercent = intent.getIntExtra("gain", 120);
            blockPhoneMic = intent.getBooleanExtra("blockPhoneMic", true);
            autoAB = intent.getBooleanExtra("autoAB", true);
        }

        startForeground(NOTIFICATION_ID, buildNotification("Preparando SCO/HFP"));

        stopBridgeInternal();
        scanCommunicationDevices();

        if (communicationDevices.isEmpty()) {
            updateNotification("Nenhuma rota Y10 SCO/HFP exposta");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire();
        }

        running = true;

        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        } catch (Exception ignored) {}

        if (Build.VERSION.SDK_INT >= 31) {
            try {
                audioManager.setCommunicationDevice(communicationDevices.get(0));
            } catch (Exception ignored) {}
        } else {
            try {
                audioManager.startBluetoothSco();
                audioManager.setBluetoothScoOn(true);
            } catch (Exception ignored) {}
        }

        if (autoAB && communicationDevices.size() >= 2) {
            startRouteAlternation();
        }

        startAudioBridge();

        return START_STICKY;
    }

    private void scanCommunicationDevices() {
        communicationDevices.clear();

        if (Build.VERSION.SDK_INT >= 31) {
            try {
                for (AudioDeviceInfo d : audioManager.getAvailableCommunicationDevices()) {
                    int t = d.getType();
                    if (t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        t == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                        if (!looksLikePhone(d)) {
                            communicationDevices.add(d);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        if (communicationDevices.isEmpty() && !blockPhoneMic && Build.VERSION.SDK_INT >= 31) {
            try {
                communicationDevices.addAll(audioManager.getAvailableCommunicationDevices());
            } catch (Exception ignored) {}
        }
    }

    private boolean looksLikePhone(AudioDeviceInfo d) {
        String name = "";
        try {
            name = String.valueOf(d.getProductName()).toLowerCase();
        } catch (Exception ignored) {}

        if (name.contains("2312dra50g")) return true;
        if (name.contains("redmi")) return true;
        if (name.contains("phone")) return true;
        if (name.contains("built-in")) return true;

        int type = d.getType();
        return type == AudioDeviceInfo.TYPE_BUILTIN_MIC ||
               type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ||
               type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
    }

    private void startRouteAlternation() {
        routeThread = new Thread(() -> {
            int idx = 0;
            while (running) {
                try {
                    AudioDeviceInfo target = communicationDevices.get(idx % communicationDevices.size());
                    if (Build.VERSION.SDK_INT >= 31) {
                        audioManager.setCommunicationDevice(target);
                    }
                    updateNotification("SCO/HFP ativo: " + safeName(target));
                    idx++;
                    Thread.sleep(2500);
                } catch (Exception e) {
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                }
            }
        }, "Y10RouteSwitch");
        routeThread.start();
    }

    private void startAudioBridge() {
        audioThread = new Thread(() -> {
            final int sampleRate = 16000;
            int recMin = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );

            int playMin = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );

            int bufferSize = Math.max(recMin, playMin) * 2;

            try {
                AudioFormat recFormat = new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build();

                recorder = new AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                        .setAudioFormat(recFormat)
                        .setBufferSizeInBytes(bufferSize)
                        .build();

                if (Build.VERSION.SDK_INT >= 23) {
                    AudioDeviceInfo preferred = firstBluetoothInput();
                    if (preferred != null) {
                        recorder.setPreferredDevice(preferred);
                    } else if (blockPhoneMic) {
                        updateNotification("Mic do celular bloqueado; nenhum mic Y10 disponível");
                        running = false;
                        stopSelf();
                        return;
                    }
                }

                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build();

                AudioFormat playFormat = new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build();

                player = new AudioTrack(
                        attrs,
                        playFormat,
                        bufferSize,
                        AudioTrack.MODE_STREAM,
                        AudioManager.AUDIO_SESSION_ID_GENERATE
                );

                recorder.startRecording();
                player.play();

                short[] buffer = new short[bufferSize / 2];

                while (running) {
                    int n = recorder.read(buffer, 0, buffer.length);
                    if (n > 0) {
                        applyGain(buffer, n, gainPercent);
                        player.write(buffer, 0, n, AudioTrack.WRITE_BLOCKING);
                    }
                }

            } catch (Exception e) {
                updateNotification("Falha SCO/HFP: " + e.getClass().getSimpleName());
            } finally {
                releaseAudio();
            }
        }, "Y10AudioBridge");
        audioThread.start();
    }

    private AudioDeviceInfo firstBluetoothInput() {
        if (Build.VERSION.SDK_INT < 23) return null;

        try {
            for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    t == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                    if (!looksLikePhone(d)) {
                        return d;
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void applyGain(short[] data, int n, int gain) {
        if (gain == 100) return;
        float factor = gain / 100f;
        for (int i = 0; i < n; i++) {
            int v = Math.round(data[i] * factor);
            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE;
            if (v < Short.MIN_VALUE) v = Short.MIN_VALUE;
            data[i] = (short) v;
        }
    }

    private String safeName(AudioDeviceInfo d) {
        try {
            return String.valueOf(d.getProductName());
        } catch (Exception e) {
            return "Bluetooth";
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }

        return b.setContentTitle("Y10 Intercom Bridge V5")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID,
                        "Y10 Intercom Bridge",
                        NotificationManager.IMPORTANCE_LOW
                );
                nm.createNotificationChannel(ch);
            }
        }
    }

    private void stopBridgeInternal() {
        running = false;
        releaseAudio();

        if (routeThread != null) {
            routeThread.interrupt();
            routeThread = null;
        }

        try {
            if (Build.VERSION.SDK_INT >= 31) {
                audioManager.clearCommunicationDevice();
            } else {
                audioManager.setBluetoothScoOn(false);
                audioManager.stopBluetoothSco();
            }
        } catch (Exception ignored) {}

        try {
            audioManager.setMode(AudioManager.MODE_NORMAL);
        } catch (Exception ignored) {}
    }

    private void releaseAudio() {
        try {
            if (recorder != null) {
                if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop();
                }
                recorder.release();
            }
        } catch (Exception ignored) {}
        recorder = null;

        try {
            if (player != null) {
                if (player.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    player.stop();
                }
                player.release();
            }
        } catch (Exception ignored) {}
        player = null;
    }

    @Override
    public void onDestroy() {
        stopBridgeInternal();

        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
