package com.example.y10bridge;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.*;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private AudioManager audioManager;
    private TextView routeStatus;
    private TextView diagnostic;
    private TextView micA;
    private TextView micB;
    private TextView outputInfo;
    private SeekBar gainBar;
    private TextView gainValue;
    private Switch blockPhoneMicSwitch;
    private Switch autoABSwitch;

    private final List<AudioDeviceInfo> commDevices = new ArrayList<>();
    private final List<AudioDeviceInfo> btOutputs = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(30, 30, 30, 50);
        root.setBackgroundColor(Color.rgb(3, 12, 20));
        scroll.addView(root);

        TextView title = makeText("Y10 INTERCOM BRIDGE V5", 28, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        TextView subtitle = makeText("SCO/HFP • 2 Y10 • mic do celular bloqueável", 16, Color.rgb(30, 180, 255), false);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 6, 0, 22);
        root.addView(subtitle, matchWrap());

        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);

        micA = makeCard("🎙 Y10 A\naguardando rota SCO/HFP");
        micB = makeCard("🎙 Y10 B\naguardando rota SCO/HFP");

        cards.addView(micA, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        cards.addView(micB, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(cards, matchWrap());

        outputInfo = makeCard("🎧 SAÍDA\nDetectando Y10 em mídia");
        root.addView(outputInfo, matchWrap());

        Button prepare = makeButton("CONECTAR / PREPARAR OS 2 Y10");
        root.addView(prepare, matchWrap());

        blockPhoneMicSwitch = new Switch(this);
        blockPhoneMicSwitch.setText("BLOQUEAR MICROFONE DO CELULAR");
        blockPhoneMicSwitch.setTextColor(Color.WHITE);
        blockPhoneMicSwitch.setTextSize(17);
        blockPhoneMicSwitch.setChecked(true);
        blockPhoneMicSwitch.setPadding(10, 20, 10, 20);
        root.addView(blockPhoneMicSwitch, matchWrap());

        autoABSwitch = new Switch(this);
        autoABSwitch.setText("ALTERNAR SCO/HFP A ↔ B AUTOMATICAMENTE");
        autoABSwitch.setTextColor(Color.WHITE);
        autoABSwitch.setTextSize(17);
        autoABSwitch.setChecked(true);
        autoABSwitch.setPadding(10, 10, 10, 20);
        root.addView(autoABSwitch, matchWrap());

        TextView gainLabel = makeText("GANHO DO MICROFONE", 18, Color.LTGRAY, true);
        gainLabel.setPadding(8, 16, 0, 0);
        root.addView(gainLabel, matchWrap());

        LinearLayout gainRow = new LinearLayout(this);
        gainRow.setOrientation(LinearLayout.HORIZONTAL);
        gainBar = new SeekBar(this);
        gainBar.setMax(300);
        gainBar.setProgress(120);
        gainValue = makeText("120%", 18, Color.rgb(30,180,255), true);
        gainValue.setGravity(Gravity.CENTER_VERTICAL);
        gainRow.addView(gainBar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        gainRow.addView(gainValue, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(gainRow, matchWrap());

        Button start = makeButton("INICIAR MODO SCO/HFP");
        start.setTextSize(20);
        root.addView(start, matchWrap());

        Button stop = makeButton("PARAR");
        root.addView(stop, matchWrap());

        routeStatus = makeCard("● Pronto para iniciar o teste SCO/HFP");
        root.addView(routeStatus, matchWrap());

        diagnostic = makeText("", 14, Color.rgb(145,165,185), false);
        diagnostic.setPadding(12, 12, 12, 30);
        root.addView(diagnostic, matchWrap());

        setContentView(scroll);

        gainBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                gainValue.setText(progress + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        prepare.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });

        start.setOnClickListener(v -> startScoMode());

        stop.setOnClickListener(v -> {
            stopService(new Intent(this, BridgeService.class));
            routeStatus.setText("● Intercom parado");
            refreshDevices();
        });

        requestPermissionsIfNeeded();
        refreshDevices();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDevices();
    }

    private void refreshDevices() {
        commDevices.clear();
        btOutputs.clear();

        if (Build.VERSION.SDK_INT >= 31) {
            try {
                for (AudioDeviceInfo d : audioManager.getAvailableCommunicationDevices()) {
                    int t = d.getType();
                    if (t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        t == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                        commDevices.add(d);
                    }
                }
            } catch (Exception ignored) {}
        }

        try {
            for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    t == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                    btOutputs.add(d);
                }
            }
        } catch (Exception ignored) {}

        micA.setText(commDevices.size() >= 1
                ? "🎙 Y10 A\nrota disponível: " + deviceName(commDevices.get(0))
                : "🎙 Y10 A\nrota SCO/HFP não exposta");

        micB.setText(commDevices.size() >= 2
                ? "🎙 Y10 B\nrota disponível: " + deviceName(commDevices.get(1))
                : "🎙 Y10 B\nrota SCO/HFP não exposta");

        outputInfo.setText("🎧 SAÍDA\n" + btOutputs.size() + " dispositivos Bluetooth detectados");

        StringBuilder sb = new StringBuilder();
        sb.append("DIAGNÓSTICO V5\n");
        sb.append("Rotas comunicação BT: ").append(commDevices.size()).append("\n");
        for (int i = 0; i < commDevices.size(); i++) {
            sb.append("COMM ").append(i+1).append(": ")
                    .append(deviceName(commDevices.get(i)))
                    .append(" type=").append(commDevices.get(i).getType()).append("\n");
        }
        sb.append("Saídas BT: ").append(btOutputs.size()).append("\n");
        for (int i = 0; i < btOutputs.size(); i++) {
            sb.append("OUT ").append(i+1).append(": ")
                    .append(deviceName(btOutputs.get(i)))
                    .append(" type=").append(btOutputs.get(i).getType()).append("\n");
        }
        sb.append("Mic do celular bloqueado: ").append(blockPhoneMicSwitch.isChecked()).append("\n");
        sb.append("Alternância A/B: ").append(autoABSwitch.isChecked()).append("\n");

        diagnostic.setText(sb.toString());
    }

    private void startScoMode() {
        if (requestPermissionsIfNeeded()) return;

        Intent i = new Intent(this, BridgeService.class);
        i.putExtra("gain", gainBar.getProgress());
        i.putExtra("blockPhoneMic", blockPhoneMicSwitch.isChecked());
        i.putExtra("autoAB", autoABSwitch.isChecked());

        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        routeStatus.setText("● Modo SCO/HFP iniciado — verifique a rota ativa");
    }

    private TextView makeText(String text, int size, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(size);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private TextView makeCard(String text) {
        TextView tv = makeText(text, 17, Color.WHITE, false);
        tv.setPadding(24, 24, 24, 24);
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundColor(Color.rgb(9, 31, 48));
        return tv;
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(18);
        b.setAllCaps(false);
        b.setBackgroundColor(Color.rgb(18, 126, 190));
        b.setPadding(10, 20, 10, 20);
        return b;
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 8, 0, 8);
        return p;
    }

    private String deviceName(AudioDeviceInfo d) {
        return d.getProductName() + " [ID " + d.getId() + "]";
    }

    private boolean requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT < 23) return false;

        List<String> permissions = new ArrayList<>();

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
        }

        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        }

        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (!permissions.isEmpty()) {
            requestPermissions(permissions.toArray(new String[0]), 100);
            return true;
        }
        return false;
    }
    }
