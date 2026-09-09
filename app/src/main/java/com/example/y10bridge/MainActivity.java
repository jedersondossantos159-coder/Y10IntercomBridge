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
    private AudioManager am;
    private Spinner micSpinner, outSpinner, modeSpinner;
    private SeekBar gainBar, voxBar;
    private TextView diag, status;
    private final List<AudioDeviceInfo> mics = new ArrayList<>();
    private final List<AudioDeviceInfo> outs = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        am = (AudioManager) getSystemService(AUDIO_SERVICE);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 32, 28, 40);
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("Y10 Intercom Bridge V2");
        title.setTextSize(26);
        root.addView(title);

        addLabel(root, "Microfone Bluetooth");
        micSpinner = new Spinner(this); root.addView(micSpinner);

        addLabel(root, "Saída Bluetooth");
        outSpinner = new Spinner(this); root.addView(outSpinner);

        addLabel(root, "Modo");
        modeSpinner = new Spinner(this);
        modeSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Normal — 1 Y10", "Dual Output — tentar os 2", "Rádio/VOX — alternar A/B"}));
        root.addView(modeSpinner);

        addLabel(root, "Ganho");
        gainBar = new SeekBar(this); gainBar.setMax(300); gainBar.setProgress(100); root.addView(gainBar);

        addLabel(root, "VOX");
        voxBar = new SeekBar(this); voxBar.setMax(100); voxBar.setProgress(20); root.addView(voxBar);

        Button refresh = new Button(this); refresh.setText("ATUALIZAR Y10"); root.addView(refresh);
        Button testA = new Button(this); testA.setText("TESTAR Y10 A"); root.addView(testA);
        Button testB = new Button(this); testB.setText("TESTAR Y10 B"); root.addView(testB);
        Button start = new Button(this); start.setText("INICIAR INTERCOM"); root.addView(start);
        Button stop = new Button(this); stop.setText("PARAR"); root.addView(stop);

        status = new TextView(this); status.setText("\nStatus: parado"); root.addView(status);
        diag = new TextView(this); root.addView(diag);

        setContentView(sv);

        refresh.setOnClickListener(v -> refreshDevices());
        testA.setOnClickListener(v -> { micSpinner.setSelection(0); startBridge(); });
        testB.setOnClickListener(v -> {
            if (mics.size() > 1) { micSpinner.setSelection(1); startBridge(); }
            else Toast.makeText(this, "Segundo Y10 de comunicação não detectado.", Toast.LENGTH_LONG).show();
        });
        start.setOnClickListener(v -> startBridge());
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, BridgeService.class));
            status.setText("\nStatus: parado");
        });

        requestPerms();
        refreshDevices();
    }

    private void addLabel(LinearLayout r, String s) {
        TextView t = new TextView(this); t.setText("\n" + s); t.setTextSize(16); r.addView(t);
    }

    private void refreshDevices() {
        mics.clear(); outs.clear();
        List<String> mn = new ArrayList<>(), on = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= 31) {
            try {
                for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                    if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        d.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                        mics.add(d); mn.add(name(d));
                    }
                }
            } catch (Exception ignored) {}
        }

        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                t == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                outs.add(d); on.add(name(d));
            }
        }

        if (mn.isEmpty()) mn.add("Nenhum Y10 de comunicação detectado");
        on.add(0, "AUTOMÁTICO / TENTAR OS DOIS");

        micSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, mn));
        outSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, on));

        StringBuilder s = new StringBuilder("\nDIAGNÓSTICO\n");
        s.append("Microfones de comunicação: ").append(mics.size()).append("\n");
        for (int i=0;i<mics.size();i++) s.append("MIC ").append(i+1).append(": ").append(name(mics.get(i))).append("\n");
        s.append("Saídas Bluetooth: ").append(outs.size()).append("\n");
        for (int i=0;i<outs.size();i++) s.append("OUT ").append(i+1).append(": ").append(name(outs.get(i))).append("\n");
        diag.setText(s.toString());
    }

    private String name(AudioDeviceInfo d) {
        return d.getProductName() + " [ID " + d.getId() + "]";
    }

    private void startBridge() {
        if (requestPerms()) return;
        Intent i = new Intent(this, BridgeService.class);
        i.putExtra("input", micSpinner.getSelectedItemPosition());
        i.putExtra("output", outSpinner.getSelectedItemPosition()-1);
        i.putExtra("mode", modeSpinner.getSelectedItemPosition());
        i.putExtra("gain", gainBar.getProgress());
        i.putExtra("vox", voxBar.getProgress());
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        status.setText("\nStatus: intercom iniciado");
    }

    private boolean requestPerms() {
        if (Build.VERSION.SDK_INT < 23) return false;
        List<String> p = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            p.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            p.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!p.isEmpty()) {
            requestPermissions(p.toArray(new String[0]), 100);
            return true;
        }
        return false;
    }
}

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
        status.setTextSize(17);

        root.addView(status);

        diagnostics = new TextView(this);
        diagnostics.setTextSize(14);

        root.addView(diagnostics);

        setContentView(scroll);

        refresh.setOnClickListener(v -> refreshDevices());

        testA.setOnClickListener(v -> {
            microphoneSpinner.setSelection(0);
            startBridge();
        });

        testB.setOnClickListener(v -> {
            if (microphones.size() >= 2) {
                microphoneSpinner.setSelection(1);
                startBridge();
            } else {
                Toast.makeText(
                        this,
                        "Segundo Y10 de comunicação não detectado.",
                        Toast.LENGTH_LONG
                ).show();
            }
        });

        start.setOnClickListener(v -> startBridge());

        stop.setOnClickListener(v -> {
            stopService(
                    new Intent(
                            this,
                            BridgeService.class
                    )
            );

            status.setText("\nStatus: parado");
        });

        requestPermissionsIfNeeded();

        refreshDevices();
    }

    private void addLabel(
            LinearLayout root,
            String text
    ) {

        TextView label = new TextView(this);

        label.setText("\n" + text);
        label.setTextSize(16);

        root.addView(label);
    }

    private void refreshDevices() {

        microphones.clear();
        outputs.clear();

        List<String> microphoneNames =
                new ArrayList<>();

        List<String> outputNames =
                new ArrayList<>();

        if (Build.VERSION.SDK_INT >= 31) {

            try {

                List<AudioDeviceInfo> devices =
                        audioManager
                                .getAvailableCommunicationDevices();

                for (AudioDeviceInfo device : devices) {

                    if (
                            device.getType()
                                    == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                                    ||
                            device.getType()
                                    == AudioDeviceInfo.TYPE_BLE_HEADSET
                    ) {

                        microphones.add(device);

                        microphoneNames.add(
                                deviceName(device)
                        );
                    }
                }

            } catch (Exception ignored) {
            }
        }

        AudioDeviceInfo[] outputDevices =
                audioManager.getDevices(
                        AudioManager.GET_DEVICES_OUTPUTS
                );

        for (AudioDeviceInfo device :
                outputDevices) {

            int type = device.getType();

            if (
                    type ==
                            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                            ||
                    type ==
                            AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                            ||
                    type ==
                            AudioDeviceInfo.TYPE_BLE_HEADSET
            ) {

                outputs.add(device);

                outputNames.add(
                        deviceName(device)
                );
            }
        }

        if (microphoneNames.isEmpty()) {

            microphoneNames.add(
                    "Nenhum Y10 de comunicação detectado"
            );
        }

        outputNames.add(
                0,
                "AUTOMÁTICO / TENTAR OS DOIS"
        );

        microphoneSpinner.setAdapter(
                new ArrayAdapter<>(
                        this,
                        android.R.layout
                                .simple_spinner_dropdown_item,
                        microphoneNames
                )
        );

        outputSpinner.setAdapter(
                new ArrayAdapter<>(
                        this,
                        android.R.layout
                                .simple_spinner_dropdown_item,
                        outputNames
                )
        );

        StringBuilder report =
                new StringBuilder();

        report.append(
                "\nDIAGNÓSTICO BLUETOOTH\n\n"
        );

        report.append(
                "Microfones de comunicação: "
        );

        report.append(
                microphones.size()
        );

        report.append("\n");

        for (int i = 0;
             i < microphones.size();
             i++) {

            report.append(
                    "MIC "
                            + (i + 1)
                            + ": "
            );

            report.append(
                    deviceName(
                            microphones.get(i)
                    )
            );

            report.append("\n");
        }

        report.append(
                "\nSaídas Bluetooth: "
        );

        report.append(
                outputs.size()
        );

        report.append("\n");

        for (int i = 0;
             i < outputs.size();
             i++) {

            report.append(
                    "OUT "
                            + (i + 1)
                            + ": "
            );

            report.append(
                    deviceName(
                            outputs.get(i)
                    )
            );

            report.append("\n");
        }

        diagnostics.setText(
                report.toString()
        );
    }

    private String deviceName(
            AudioDeviceInfo device
    ) {

        return device.getProductName()
                + "  [ID "
                + device.getId()
                + "]";
    }

    private void startBridge() {

        if (requestPermissionsIfNeeded()) {
            return;
        }

        Intent intent =
                new Intent(
                        this,
                        BridgeService.class
                );

        intent.putExtra(
                "input",
                microphoneSpinner
                        .getSelectedItemPosition()
        );

        intent.putExtra(
                "output",
                outputSpinner
                        .getSelectedItemPosition() - 1
        );

        intent.putExtra(
                "mode",
                modeSpinner
                        .getSelectedItemPosition()
        );

        intent.putExtra(
                "gain",
                gainBar.getProgress()
        );

        intent.putExtra(
                "vox",
                voxBar.getProgress()
        );

        if (Build.VERSION.SDK_INT >= 26) {

            startForegroundService(intent);

        } else {

            startService(intent);
        }

        status.setText(
                "\nStatus: intercom iniciado"
        );
    }

    private boolean requestPermissionsIfNeeded() {

        if (Build.VERSION.SDK_INT < 23) {
            return false;
        }

        List<String> permissions =
                new ArrayList<>();

        if (
                checkSelfPermission(
                        Manifest.permission.RECORD_AUDIO
                )
                        != PackageManager
                        .PERMISSION_GRANTED
        ) {

            permissions.add(
                    Manifest.permission.RECORD_AUDIO
            );
        }

        if (
                Build.VERSION.SDK_INT >= 31
                        &&
                checkSelfPermission(
                        Manifest.permission
                                .BLUETOOTH_CONNECT
                )
                        != PackageManager
                        .PERMISSION_GRANTED
        ) {

            permissions.add(
                    Manifest.permission
                            .BLUETOOTH_CONNECT
            );
        }

        if (
                Build.VERSION.SDK_INT >= 33
                        &&
                checkSelfPermission(
                        Manifest.permission
                                .POST_NOTIFICATIONS
                )
                        != PackageManager
                        .PERMISSION_GRANTED
        ) {

            permissions.add(
                    Manifest.permission
                            .POST_NOTIFICATIONS
            );
        }

        if (!permissions.isEmpty()) {

            requestPermissions(
                    permissions.toArray(
                            new String[0]
                    ),
                    100
            );

            return true;
        }

        return false;
    }
}
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
        status.setTextSize(17);

        root.addView(status);

        diagnostics = new TextView(this);
        diagnostics.setTextSize(14);

        root.addView(diagnostics);

        setContentView(scroll);

        refresh.setOnClickListener(v -> refreshDevices());

        testA.setOnClickListener(v -> {
            microphoneSpinner.setSelection(0);
            startBridge();
        });

        testB.setOnClickListener(v -> {
            if (microphones.size() >= 2) {
                microphoneSpinner.setSelection(1);
                startBridge();
            } else {
                Toast.makeText(
                        this,
                        "Segundo Y10 de comunicação não detectado.",
                        Toast.LENGTH_LONG
                ).show();
            }
        });

        start.setOnClickListener(v -> startBridge());

        stop.setOnClickListener(v -> {
            stopService(
                    new Intent(
                            this,
                            BridgeService.class
                    )
            );

            status.setText("\nStatus: parado");
        });

        requestPermissionsIfNeeded();

        refreshDevices();
    }

    private void addLabel(
            LinearLayout root,
            String text
    ) {

        TextView label = new TextView(this);

        label.setText("\n" + text);
        label.setTextSize(16);

        root.addView(label);
    }

    private void refreshDevices() {

        microphones.clear();
        outputs.clear();

        List<String> microphoneNames =
                new ArrayList<>();

        List<String> outputNames =
                new ArrayList<>();

        if (Build.VERSION.SDK_INT >= 31) {

            try {

                List<AudioDeviceInfo> devices =
                        audioManager
                                .getAvailableCommunicationDevices();

                for (AudioDeviceInfo device : devices) {

                    if (
                            device.getType()
                                    == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                                    ||
                            device.getType()
                                    == AudioDeviceInfo.TYPE_BLE_HEADSET
                    ) {

                        microphones.add(device);

                        microphoneNames.add(
                                deviceName(device)
                        );
                    }
                }

            } catch (Exception ignored) {
            }
        }

        AudioDeviceInfo[] outputDevices =
                audioManager.getDevices(
                        AudioManager.GET_DEVICES_OUTPUTS
                );

        for (AudioDeviceInfo device :
                outputDevices) {

            int type = device.getType();

            if (
                    type ==
                            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                            ||
                    type ==
                            AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                            ||
                    type ==
                            AudioDeviceInfo.TYPE_BLE_HEADSET
            ) {

                outputs.add(device);

                outputNames.add(
                        deviceName(device)
                );
            }
        }

        if (microphoneNames.isEmpty()) {

            microphoneNames.add(
                    "Nenhum Y10 de comunicação detectado"
            );
        }

        outputNames.add(
                0,
                "AUTOMÁTICO / TENTAR OS DOIS"
        );

        microphoneSpinner.setAdapter(
                new ArrayAdapter<>(
                        this,
                        android.R.layout
                                .simple_spinner_dropdown_item,
                        microphoneNames
                )
        );

        outputSpinner.setAdapter(
                new ArrayAdapter<>(
                        this,
                        android.R.layout
                                .simple_spinner_dropdown_item,
                        outputNames
                )
        );

        StringBuilder report =
                new StringBuilder();

        report.append(
                "\nDIAGNÓSTICO BLUETOOTH\n\n"
        );

        report.append(
                "Microfones de comunicação: "
        );

        report.append(
                microphones.size()
        );

        report.append("\n");

        for (int i = 0;
             i < microphones.size();
             i++) {

            report.append(
                    "MIC "
                            + (i + 1)
                            + ": "
            );

            report.append(
                    deviceName(
                            microphones.get(i)
                    )
            );

            report.append("\n");
        }

        report.append(
                "\nSaídas Bluetooth: "
        );

        report.append(
                outputs.size()
        );

        report.append("\n");

        for (int i = 0;
             i < outputs.size();
             i++) {

            report.append(
                    "OUT "
                            + (i + 1)
                            + ": "
            );

            report.append(
                    deviceName(
                            outputs.get(i)
                    )
            );

            report.append("\n");
        }

        diagnostics.setText(
                report.toString()
        );
    }

    private String deviceName(
            AudioDeviceInfo device
    ) {

        return device.getProductName()
                + "  [ID "
                + device.getId()
                + "]";
    }

    private void startBridge() {

        if (requestPermissionsIfNeeded()) {
            return;
        }

        Intent intent =
                new Intent(
                        this,
                        BridgeService.class
                );

        intent.putExtra(
                "input",
                microphoneSpinner
                        .getSelectedItemPosition()
        );

        intent.putExtra(
                "output",
                outputSpinner
                        .getSelectedItemPosition() - 1
        );

        intent.putExtra(
                "mode",
                modeSpinner
                        .getSelectedItemPosition()
        );

        intent.putExtra(
                "gain",
                gainBar.getProgress()
        );

        intent.putExtra(
                "vox",
                voxBar.getProgress()
        );

        if (Build.VERSION.SDK_INT >= 26) {

            startForegroundService(intent);

        } else {

            startService(intent);
        }

        status.setText(
                "\nStatus: intercom iniciado"
        );
    }

    private boolean requestPermissionsIfNeeded() {

        if (Build.VERSION.SDK_INT < 23) {
            return false;
        }

        List<String> permissions =
                new ArrayList<>();

        if (
                checkSelfPermission(
                        Manifest.permission.RECORD_AUDIO
                )
                        != PackageManager
                        .PERMISSION_GRANTED
        ) {

            permissions.add(
                    Manifest.permission.RECORD_AUDIO
            );
        }

        if (
                Build.VERSION.SDK_INT >= 31
                        &&
                checkSelfPermission(
                        Manifest.permission
                                .BLUETOOTH_CONNECT
                )
                        != PackageManager
                        .PERMISSION_GRANTED
        ) {

            permissions.add(
                    Manifest.permission
                            .BLUETOOTH_CONNECT
            );
        }

        if (
                Build.VERSION.SDK_INT >= 33
                        &&
                checkSelfPermission(
                        Manifest.permission
                                .POST_NOTIFICATIONS
                )
                        != PackageManager
                        .PERMISSION_GRANTED
        ) {

            permissions.add(
                    Manifest.permission
                            .POST_NOTIFICATIONS
            );
        }

        if (!permissions.isEmpty()) {

            requestPermissions(
                    permissions.toArray(
                            new String[0]
                    ),
                    100
            );

            return true;
        }

        return false
