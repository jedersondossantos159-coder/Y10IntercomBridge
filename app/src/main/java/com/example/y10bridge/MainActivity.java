package com.example.y10bridge;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.*;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private AudioManager audioManager;
    private Spinner micSpinner;
    private Spinner outSpinner;
    private Spinner modeSpinner;
    private SeekBar gainBar;
    private SeekBar voxBar;
    private TextView diagnostic;
    private TextView status;

    private final List<AudioDeviceInfo> microphones = new ArrayList<>();
    private final List<AudioDeviceInfo> outputs = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(30, 30, 30, 40);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Y10 Intercom Bridge V2");
        title.setTextSize(26);
        root.addView(title);

        root.addView(makeLabel("Microfone Bluetooth"));
        micSpinner = new Spinner(this);
        root.addView(micSpinner);

        root.addView(makeLabel("Saída Bluetooth"));
        outSpinner = new Spinner(this);
        root.addView(outSpinner);

        root.addView(makeLabel("Modo"));
        modeSpinner = new Spinner(this);
        modeSpinner.setAdapter(new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[] {
                        "Normal - 1 Y10",
                        "Dual Output - tentar os 2",
                        "Radio/VOX - alternar A/B"
                }
        ));
        root.addView(modeSpinner);

        root.addView(makeLabel("Ganho"));
        gainBar = new SeekBar(this);
        gainBar.setMax(300);
        gainBar.setProgress(100);
        root.addView(gainBar);

        root.addView(makeLabel("Sensibilidade VOX"));
        voxBar = new SeekBar(this);
        voxBar.setMax(100);
        voxBar.setProgress(20);
        root.addView(voxBar);

        Button refresh = new Button(this);
        refresh.setText("ATUALIZAR Y10");
        root.addView(refresh);

        Button testA = new Button(this);
        testA.setText("TESTAR Y10 A");
        root.addView(testA);

        Button testB = new Button(this);
        testB.setText("TESTAR Y10 B");
        root.addView(testB);

        Button start = new Button(this);
        start.setText("INICIAR INTERCOM");
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("PARAR");
        root.addView(stop);

        status = new TextView(this);
        status.setText("\nStatus: parado");
        root.addView(status);

        diagnostic = new TextView(this);
        diagnostic.setTextSize(14);
        root.addView(diagnostic);

        setContentView(scroll);

        refresh.setOnClickListener(v -> refreshDevices());

        testA.setOnClickListener(v -> {
            if (!microphones.isEmpty()) {
                micSpinner.setSelection(0);
                startBridge();
            }
        });

        testB.setOnClickListener(v -> {
            if (microphones.size() >= 2) {
                micSpinner.setSelection(1);
                startBridge();
            } else {
                Toast.makeText(
                        this,
                        "Segundo Y10 de comunicacao nao detectado.",
                        Toast.LENGTH_LONG
                ).show();
            }
        });

        start.setOnClickListener(v -> startBridge());

        stop.setOnClickListener(v -> {
            stopService(new Intent(this, BridgeService.class));
            status.setText("\nStatus: parado");
        });

        requestPermissionsIfNeeded();
        refreshDevices();
    }

    private TextView makeLabel(String text) {
        TextView label = new TextView(this);
        label.setText("\n" + text);
        label.setTextSize(16);
        return label;
    }

    private void refreshDevices() {
        microphones.clear();
        outputs.clear();

        List<String> micNames = new ArrayList<>();
        List<String> outNames = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= 31) {
            try {
                for (AudioDeviceInfo d : audioManager.getAvailableCommunicationDevices()) {
                    int type = d.getType();
                    if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                        microphones.add(d);
                        micNames.add(deviceName(d));
                    }
                }
            } catch (Exception ignored) {
            }
        }

        for (AudioDeviceInfo d :
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int type = d.getType();

            if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                outputs.add(d);
                outNames.add(deviceName(d));
            }
        }

        if (micNames.isEmpty()) {
            micNames.add("Nenhum Y10 de comunicacao detectado");
        }

        outNames.add(0, "AUTOMATICO / TENTAR OS DOIS");

        micSpinner.setAdapter(new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                micNames
        ));

        outSpinner.setAdapter(new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                outNames
        ));

        StringBuilder s = new StringBuilder();
        s.append("\nDIAGNOSTICO BLUETOOTH\n");
        s.append("Microfones de comunicacao: ")
                .append(microphones.size())
                .append("\n");

        for (int i = 0; i < microphones.size(); i++) {
            s.append("MIC ")
                    .append(i + 1)
                    .append(": ")
                    .append(deviceName(microphones.get(i)))
                    .append("\n");
        }

        s.append("Saidas Bluetooth: ")
                .append(outputs.size())
                .append("\n");

        for (int i = 0; i < outputs.size(); i++) {
            s.append("OUT ")
                    .append(i + 1)
                    .append(": ")
                    .append(deviceName(outputs.get(i)))
                    .append("\n");
        }

        diagnostic.setText(s.toString());
    }

    private String deviceName(AudioDeviceInfo d) {
        return d.getProductName() + " [ID " + d.getId() + "]";
    }

    private void startBridge() {
        if (requestPermissionsIfNeeded()) {
            return;
        }

        Intent intent = new Intent(this, BridgeService.class);
        intent.putExtra("input", micSpinner.getSelectedItemPosition());
        intent.putExtra("output", outSpinner.getSelectedItemPosition() - 1);
        intent.putExtra("mode", modeSpinner.getSelectedItemPosition());
        intent.putExtra("gain", gainBar.getProgress());
        intent.putExtra("vox", voxBar.getProgress());

        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        status.setText("\nStatus: intercom iniciado");
    }

    private boolean requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT < 23) {
            return false;
        }

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
            requestPermissions(
                    permissions.toArray(new String[0]),
                    100
            );
            return true;
        }

        return false;
    }
}
