package com.example.y10bridge;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 60, 40, 40);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Y10 Intercom Bridge");
        title.setTextSize(26);
        layout.addView(title);

        TextView info = new TextView(this);
        info.setText(
                "\nConecte os dois Y10 ao Bluetooth antes de iniciar.\n"
        );
        info.setTextSize(16);
        layout.addView(info);

        Button start = new Button(this);
        start.setText("INICIAR INTERCOM");
        layout.addView(start);

        Button stop = new Button(this);
        stop.setText("PARAR");
        layout.addView(stop);

        status = new TextView(this);
        status.setText("\nStatus: parado");
        layout.addView(status);

        start.setOnClickListener(v -> {
            if (requestPermissionsIfNeeded()) {
                return;
            }

            Intent intent =
                    new Intent(this, BridgeService.class);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }

            status.setText("\nStatus: intercom ativo");
        });

        stop.setOnClickListener(v -> {
            stopService(
                    new Intent(this, BridgeService.class)
            );

            status.setText("\nStatus: parado");
        });

        setContentView(layout);
    }

    private boolean requestPermissionsIfNeeded() {

        if (Build.VERSION.SDK_INT < 23) {
            return false;
        }

        List<String> permissions = new ArrayList<>();

        if (checkSelfPermission(
                Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {

            permissions.add(
                    Manifest.permission.RECORD_AUDIO);
        }

        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(
                        Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {

            permissions.add(
                    Manifest.permission.BLUETOOTH_CONNECT);
        }

        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(
                        Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {

            permissions.add(
                    Manifest.permission.POST_NOTIFICATIONS);
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
