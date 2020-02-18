package com.matt.arcore.java.sharedcamera_example;

import androidx.appcompat.app.AppCompatActivity;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ConfigurationInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import com.matt.arcore.java.R;
import com.matt.arcore.java.common.helpers.MiscUtils;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = MainActivity.class.getSimpleName();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.sharedcamerastart);
        MiscUtils.verifyStoragePermissions(this);

        final ActivityManager activityManager =
                (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        final ConfigurationInfo configurationInfo =
                activityManager.getDeviceConfigurationInfo();
        String versionText = "Device Supported OpenGL ES Version = " + configurationInfo.getGlEsVersion();
        Toast.makeText(this, versionText, Toast.LENGTH_LONG).show();
        Log.v(TAG +  "graphics:",versionText);

    }

    public void startClicked(View v){
        Intent myIntent = new Intent(this, SharedCameraActivity.class);
        startActivity(myIntent);
    }
}
