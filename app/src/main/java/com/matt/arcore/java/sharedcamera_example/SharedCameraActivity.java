/*
 * Copyright 2018 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */



package com.matt.arcore.java.sharedcamera_example;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.google.ar.core.Session;
import com.google.ar.core.SharedCamera;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableException;

import com.matt.arcore.java.R;
import com.matt.arcore.java.common.helpers.CameraPermissionHelper;
import com.matt.arcore.java.common.helpers.DisplayRotationHelper;
import com.matt.arcore.java.common.helpers.FullScreenHelper;
import com.matt.arcore.java.common.helpers.TrackingStateHelper;
import com.matt.arcore.java.common.rendering.BackgroundRenderer;


import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import javax.vecmath.Vector2f;


/**
 * This is a simple example that demonstrates how to use the Camera2 API while sharing camera access
 * with ARCore. An on-screen switch can be used to pause and resume ARCore. The app utilizes a
 * trivial sepia camera effect while ARCore is paused, and seamlessly hands camera capture request
 * control over to ARCore when it is running.
 */
public class SharedCameraActivity extends AppCompatActivity implements GLSurfaceView.Renderer {
    private static final String TAG = SharedCameraActivity.class.getSimpleName();


    // Depth TOF Image.
    // Use 240 * 180 for now, hardcoded for Huawei P30 Pro
    private static final int DEPTH_WIDTH = 240;
    private static final int DEPTH_HEIGHT = 180;

    // background image rendering
    public Vector2f screenResolution;
    private SeekBar depthSeekBar;
    private int depthThresh;

    private Button vizModeNext;
    private Button vizModePrev;

    //***************************************************************************************************************************
    // ********************************************Rendering and pose - updated each frame - native buffers for sharing with C/C++
    //***************************************************************************************************************************
    // projection matrix.
    public float[] projmtx = new float[16];
    // camera matrix
    public float[] viewmtx = new float[16];

    //***************************************************************************************************************************


    // Whether the surface texture has been attached to the GL context.
    boolean isGlAttached;

    // GL Surface used to draw camera preview image.
    public GLSurfaceView surfaceView;

    // ARCore session that supports camera sharing.
    private Session sharedSession;

    // Camera capture session. Used by both non-AR and AR modes.
    private CameraCaptureSession captureSession;

    // Reference to the camera system service.
    private CameraManager cameraManager;

    // Camera device. Used by both non-AR and AR modes.
    private CameraDevice cameraDevice;

    // Looper handler thread.
    private HandlerThread backgroundThread;
    // Looper handler.
    private Handler backgroundHandler;

    // ARCore shared camera instance, obtained from ARCore session that supports sharing.
    private SharedCamera sharedCamera;

    // Camera ID for the camera used by ARCore.
    private String cameraId;

    // Ensure GL surface draws only occur when new frames are available.
    private final AtomicBoolean shouldUpdateSurfaceTexture = new AtomicBoolean(false);


    // Whether the GL surface has been created.
    private boolean surfaceCreated;

    // Camera preview capture request builder
    private CaptureRequest.Builder previewCaptureRequestBuilder;

    // Image reader that continuously processes CPU images.
    public TOF_ImageReader TOFImageReader;
    private boolean TOF_available = false;

    // Various helper classes, see hello_ar_java sample to learn more.
    private DisplayRotationHelper displayRotationHelper;

    // Renderers, see hello_ar_java sample to learn more.
    private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer(this);

    // Prevent any changes to camera capture session after CameraManager.openCamera() is called, but
    // before camera device becomes active.
    private boolean captureSessionChangesPossible = true;

    // A check mechanism to ensure that the camera closed properly so that the app can safely exit.
    private final ConditionVariable safeToExitApp = new ConditionVariable();
    private int vizMode;
    private TextView vizModeTextView;
    private AssetManager assetManager;
    private TrackingStateHelper trackingStateHelper;

    // Camera device state callback.
    private final CameraDevice.StateCallback cameraDeviceCallback =
            new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice cameraDevice) {
                    Log.d(TAG, "Camera device ID " + cameraDevice.getId() + " opened.");
                    SharedCameraActivity.this.cameraDevice = cameraDevice;
                    createCameraPreviewSession();
                }

                @Override
                public void onClosed(@NonNull CameraDevice cameraDevice) {
                    Log.d(TAG, "Camera device ID " + cameraDevice.getId() + " closed.");
                    SharedCameraActivity.this.cameraDevice = null;
                    safeToExitApp.open();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice cameraDevice) {
                    Log.w(TAG, "Camera device ID " + cameraDevice.getId() + " disconnected.");
                    cameraDevice.close();
                    SharedCameraActivity.this.cameraDevice = null;
                }

                @Override
                public void onError(@NonNull CameraDevice cameraDevice, int error) {
                    Log.e(TAG, "Camera device ID " + cameraDevice.getId() + " error " + error);
                    cameraDevice.close();
                    SharedCameraActivity.this.cameraDevice = null;
                    // Fatal error. Quit application.
                    finish();
                }
            };

    // Repeating camera capture session state callback.
    CameraCaptureSession.StateCallback cameraCaptureCallback =
            new CameraCaptureSession.StateCallback() {

                // Called when the camera capture session is first configured after the app
                // is initialized, and again each time the activity is resumed.
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    Log.d(TAG, "Camera capture session configured.");
                    captureSession = session;
                    setRepeatingCaptureRequest();
                }

                @Override
                public void onSurfacePrepared(
                        @NonNull CameraCaptureSession session, @NonNull Surface surface) {
                    Log.d(TAG, "Camera capture surface prepared.");
                }

                @Override
                public void onReady(@NonNull CameraCaptureSession session) {
                    Log.d(TAG, "Camera capture session ready.");
                }

                @Override
                public void onActive(@NonNull CameraCaptureSession session) {
                    Log.d(TAG, "Camera capture session active.");
                    resumeARCore();
                    synchronized (SharedCameraActivity.this) {
                        captureSessionChangesPossible = true;
                        SharedCameraActivity.this.notify();
                    }
                }

                @Override
                public void onCaptureQueueEmpty(@NonNull CameraCaptureSession session) {
                    Log.w(TAG, "Camera capture queue empty.");
                }

                @Override
                public void onClosed(@NonNull CameraCaptureSession session) {
                    Log.d(TAG, "Camera capture session closed.");
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "Failed to configure camera capture session.");
                }
            };

    // Repeating camera capture session capture callback.
    private final CameraCaptureSession.CaptureCallback captureSessionCallback =
            new CameraCaptureSession.CaptureCallback() {

                @Override
                public void onCaptureCompleted(
                        @NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request,
                        @NonNull TotalCaptureResult result) {
                    shouldUpdateSurfaceTexture.set(true);
                }

                @Override
                public void onCaptureBufferLost(
                        @NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request,
                        @NonNull Surface target,
                        long frameNumber) {
                    Log.e(TAG, "onCaptureBufferLost: " + frameNumber);
                }

                @Override
                public void onCaptureFailed(
                        @NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request,
                        @NonNull CaptureFailure failure) {
                    Log.e(TAG, "onCaptureFailed: " + failure.getFrameNumber() + " " + failure.getReason());
                }

                @Override
                public void onCaptureSequenceAborted(
                        @NonNull CameraCaptureSession session, int sequenceId) {
                    Log.e(TAG, "onCaptureSequenceAborted: " + sequenceId + " " + session);
                }
            };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.v(TAG + "sharedcamera activity starting.", "onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        assetManager = this.getAssets();
        trackingStateHelper = new TrackingStateHelper(this);
        // GL surface view that renders camera preview image.
        surfaceView = findViewById(R.id.glsurfaceview);
        surfaceView.setPreserveEGLContextOnPause(true);
        surfaceView.setEGLContextClientVersion(2);
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        surfaceView.setRenderer(this);
        surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        surfaceView.setDebugFlags(GLSurfaceView.DEBUG_CHECK_GL_ERROR | GLSurfaceView.DEBUG_LOG_GL_CALLS);

        // Helpers, see hello_ar_java sample to learn more.
        displayRotationHelper = new DisplayRotationHelper(this);

        vizModeTextView = findViewById(R.id.vizModeText);

        vizModeNext = findViewById(R.id.vizModeNext);
        vizModeNext.setOnClickListener(new Button.OnClickListener(){
            @Override
            public void onClick(View v) {
                vizMode++;
                vizModeTextView.setText("View Mode: " + vizMode);
            }
        });

        vizModePrev = findViewById(R.id.vizModePrev);
        vizModePrev.setOnClickListener(new Button.OnClickListener(){
            @Override
            public void onClick(View v) {
                vizMode--;
                if(vizMode < 0) vizMode = 0;
                vizModeTextView.setText("View Mode: " + vizMode);
            }
        });


        depthSeekBar = findViewById(R.id.depthSeekBar);
        depthThresh = depthSeekBar.getProgress();

        depthSeekBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progressValue, boolean fromUser) {
                        depthThresh = progressValue;
                        if (depthThresh == 0) {
                            depthThresh = 1;
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                    }
                });


        TOFImageReader = new TOF_ImageReader();

        displayRotationHelper.onResume();

    }


    private synchronized void waitUntilCameraCaptureSesssionIsActive() {
        while (!captureSessionChangesPossible) {
            try {
                this.wait();
            } catch (InterruptedException e) {
                Log.e(TAG, "Unable to wait for a safe time to make changes to the capture session", e);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        waitUntilCameraCaptureSesssionIsActive();
        startBackgroundThread();
        surfaceView.onResume();

        // When the activity starts and resumes for the first time, openCamera() will be called
        // from onSurfaceCreated(). In subsequent resumes we call openCamera() here.
        if (surfaceCreated) {
            openCamera();
        }

        displayRotationHelper.onResume();


    }

    @Override
    public void onPause() {
        surfaceView.onPause();
        waitUntilCameraCaptureSesssionIsActive();
        displayRotationHelper.onPause();
        pauseARCore();
        closeCamera();
        stopBackgroundThread();
        super.onPause();


    }


    private void resumeARCore() {
        // Ensure that session is valid before triggering ARCore resume. Handles the case where the user
        // manually uninstalls ARCore while the app is paused and then resumes.
        if (sharedSession == null) {
            return;
        }

        try {
            // Resume ARCore.
            sharedSession.resume();
            // Set capture session callback while in AR mode.
            sharedCamera.setCaptureCallback(captureSessionCallback, backgroundHandler);
        } catch (CameraNotAvailableException e) {
            Log.e(TAG, "Failed to resume ARCore session", e);
            return;
        }

    }

    private void pauseARCore() {
        if (shouldUpdateSurfaceTexture != null) shouldUpdateSurfaceTexture.set(false);
        if (sharedSession != null) sharedSession.pause();
    }


    // Called when starting non-AR mode or switching to non-AR mode.
    // Also called when app starts in AR mode, or resumes in AR mode.
    private void setRepeatingCaptureRequest() {
        try {
            captureSession.setRepeatingRequest(
                    previewCaptureRequestBuilder.build(), captureSessionCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to set repeating request", e);
        }
    }


    private void createCameraPreviewSession() {
        Log.v("TAG" + " createCameraPreviewSession: ", "starting camera preview session.");
        Log.e("TAG" + " createCameraPreviewSession: ", "starting camera preview session.");
        try {
            // Note that isGlAttached will be set to true in AR mode in onDrawFrame().
            sharedSession.setCameraTextureName(backgroundRenderer.getCameraTextureId());

            // Create an ARCore compatible capture request using `TEMPLATE_RECORD`.
            previewCaptureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);

            // Build surfaces list, starting with ARCore provided surfaces.
            List<Surface> surfaceList = sharedCamera.getArCoreSurfaces();
            Log.e("TAG" + " createCameraPreviewSession: ", "surfaceList: sharedCamera.getArCoreSurfaces(): " + surfaceList.size());

            // Add a CPU image reader surface. On devices that don't support CPU image access, the image
            // may arrive significantly later, or not arrive at all.
            if (TOF_available) surfaceList.add(TOFImageReader.imageReader.getSurface());
            // Surface list should now contain three surfaces:
            // 0. sharedCamera.getSurfaceTexture()
            // 1. …
            // 2. depthImageReader.getSurface()

            // Add ARCore surfaces and CPU image surface targets.
            for (Surface surface : surfaceList) {
                previewCaptureRequestBuilder.addTarget(surface);
            }

            // Wrap our callback in a shared camera callback.
            CameraCaptureSession.StateCallback wrappedCallback = sharedCamera.createARSessionStateCallback(cameraCaptureCallback, backgroundHandler);

            // Create camera capture session for camera preview using ARCore wrapped callback.
            cameraDevice.createCaptureSession(surfaceList, wrappedCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "CameraAccessException", e);
        }


    }

    // Start background handler thread, used to run callbacks without blocking UI thread.
    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("sharedCameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
        TOFImageReader.startBackgroundThread();
    }

    // Stop background handler thread.
    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted while trying to join background handler thread", e);
            }
        }
        TOFImageReader.stopBackgroundThread();
    }

    // Perform various checks, then open camera device and create CPU image reader.
    private void openCamera() {

        Log.v(TAG + " opencamera: ", "Perform various checks, then open camera device and create CPU image reader.");
        // Don't open camera if already opened.
        if (cameraDevice != null) {
            return;
        }

        // Verify CAMERA_PERMISSION has been granted.
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            CameraPermissionHelper.requestCameraPermission(this);
            return;
        }

        // Make sure that ARCore is installed, up to date, and supported on this device.
        if (!isARCoreSupportedAndUpToDate()) {
            return;
        }

        if (sharedSession == null) {
            try {
                // Create ARCore session that supports camera sharing.
                sharedSession = new Session(this, EnumSet.of(Session.Feature.SHARED_CAMERA));
            } catch (UnavailableException e) {
                Log.e(TAG, "Failed to create ARCore session that supports camera sharing", e);
                return;
            }

            // Enable auto focus mode while ARCore is running.
            Config config = sharedSession.getConfig();
            config.setFocusMode(Config.FocusMode.AUTO);
            config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
            config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL);
            config.setLightEstimationMode(Config.LightEstimationMode.DISABLED);
            config.setCloudAnchorMode(Config.CloudAnchorMode.ENABLED);
            sharedSession.configure(config);

        }

        // Store the ARCore shared camera reference.
        sharedCamera = sharedSession.getSharedCamera();
        // Store the ID of the camera used by ARCore.
        cameraId = sharedSession.getCameraConfig().getCameraId();
        initCamera(this, cameraId, 1);
        ArrayList<String> resolutions;

        TOF_available = false;

        resolutions = getResolutions(this, cameraId, ImageFormat.DEPTH16);
        if (resolutions != null) {
            resolutions.forEach((temp) -> {
                Log.v(TAG + "DEPTH16 resolution: ", temp);
            });
            if (resolutions.size() > 0) TOF_available = true;
        }

        // Color CPU Image.
        // Use the currently configured CPU image size.
        //Size desiredCPUImageSize = sharedSession.getCameraConfig().getImageSize();

        if (TOF_available) TOFImageReader.createImageReader(DEPTH_WIDTH, DEPTH_HEIGHT);

        // When ARCore is running, make sure it also updates our CPU image surface.
        if (TOF_available) {
            sharedCamera.setAppSurfaces(this.cameraId, Arrays.asList(TOFImageReader.imageReader.getSurface()));
        }

        try {

            // Wrap our callback in a shared camera callback.
            CameraDevice.StateCallback wrappedCallback = sharedCamera.createARDeviceStateCallback(cameraDeviceCallback, backgroundHandler);

            // Store a reference to the camera system service.
            cameraManager = (CameraManager) this.getSystemService(Context.CAMERA_SERVICE);

            // Get the characteristics for the ARCore camera.
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(this.cameraId);

            // Prevent app crashes due to quick operations on camera open / close by waiting for the
            // capture session's onActive() callback to be triggered.
            captureSessionChangesPossible = false;

            // Open the camera device using the ARCore wrapped callback.
            cameraManager.openCamera(cameraId, wrappedCallback, backgroundHandler);
        } catch (CameraAccessException | IllegalArgumentException | SecurityException e) {
            Log.e(TAG, "Failed to open camera", e);
        }

        Log.v(TAG + " opencamera: ", "TOF_available: " + TOF_available);
    }


    // Close the camera device.
    private void closeCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            waitUntilCameraCaptureSesssionIsActive();
            safeToExitApp.close();
            cameraDevice.close();
            safeToExitApp.block();
        }

        if (TOFImageReader.imageReader != null) {
            TOFImageReader.imageReader.close();
            TOFImageReader.imageReader = null;
        }
    }


    // Android permission request callback.
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(
                    getApplicationContext(),
                    "Camera permission is needed to run this application",
                    Toast.LENGTH_LONG)
                    .show();
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                CameraPermissionHelper.launchPermissionSettings(this);
            }
            finish();
        }
    }

    // Android focus change callback.
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus);
    }


    // GL surface created callback. Will be called on the GL thread.
    @SuppressLint("WrongViewCast")
    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        surfaceCreated = true;

        // Set GL clear color to black.
        GLES20.glClearColor(0f, 0f, 0f, 1.0f);

        // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
        try {
            // Create the camera preview image texture. Used in non-AR and AR mode.
            backgroundRenderer.createOnGlThread(assetManager);

      /*virtualObjectShadow.createOnGlThread(
          this, "models/andy_shadow.obj", "models/andy_shadow.png");
      virtualObjectShadow.setBlendMode(BlendMode.Shadow);
      virtualObjectShadow.setMaterialProperties(1.0f, 0.0f, 0.0f, 1.0f);
      */

            openCamera();
        } catch (IOException e) {
            Log.e(TAG, "Failed to read an asset file", e);
        }
    }

    // GL surface changed callback. Will be called on the GL thread.
    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        displayRotationHelper.onSurfaceChanged(width, height);

        runOnUiThread(
                () -> {
                    // Adjust layout based on display orientation.
                });
    }

    // GL draw callback. Will be called each frame on the GL thread.
    @Override
    public void onDrawFrame(GL10 gl) {
        // Use the cGL clear color specified in onSurfaceCreated() to erase the GL surface.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        if (!shouldUpdateSurfaceTexture.get()) {
            // Not ready to draw.
            return;
        }
        // Handle display rotations.
        displayRotationHelper.updateSessionIfNeeded(sharedSession);

        try {
            onDrawFrameARCore();
        } catch (Throwable t) {
            // Avoid crashing the application due to unhandled exceptions.
            Log.e(TAG, "Exception on the OpenGL thread", t);
        }
    }


    // Account for any difference between camera sensor orientation and display orientation.
    public int getCameraSensorToDisplayRotation() {
        int rotationDegrees = displayRotationHelper.getCameraSensorToDisplayRotation(cameraId);
        return rotationDegrees;
    }


    /*************************************************** ONDRAWFRAME ARCORE ************************************************************* */


    // Draw frame when in AR mode. Called on the GL thread.
    public void onDrawFrameARCore() throws CameraNotAvailableException {

        if (TOF_available && TOFImageReader.frameCount == 0) return;
        screenResolution = new Vector2f(surfaceView.getWidth(), surfaceView.getHeight());


        // Perform ARCore per-frame update.
        Frame frame = null;
        try {
            frame = sharedSession.update();
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        Camera camera = null;
        if (frame != null) {
          camera = frame.getCamera();
        }else
          {
            return;
          }

            if (camera == null) return;
            // If not tracking, don't draw 3D objects.
            if (camera.getTrackingState() == TrackingState.PAUSED) return;

            // ARCore attached the surface to GL context using the texture ID we provided
            // in createCameraPreviewSession() via sharedSession.setCameraTextureName(…).
            isGlAttached = true;

            // If frame is ready, render camera preview image to the GL surface.
            // render background with occlusion.
            // Get projection matrix.

            camera.getProjectionMatrix(projmtx, 0, 0.01f, 100.0f);
            camera.getViewMatrix(viewmtx, 0);

            backgroundRenderer.draw(frame, vizMode, depthThresh);

            // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
            trackingStateHelper.updateKeepScreenOnFlag(camera.getTrackingState());

        }

        /********************************************************************************************************************* */
        /*************************************************** End ************************************************************* */
        /********************************************************************************************************************* */


        private boolean isARCoreSupportedAndUpToDate () {
            // Make sure ARCore is installed and supported on this device.
            ArCoreApk.Availability availability = ArCoreApk.getInstance().checkAvailability(this);
            switch (availability) {
                case SUPPORTED_INSTALLED:
                    break;
                case SUPPORTED_APK_TOO_OLD:
                case SUPPORTED_NOT_INSTALLED:
                    try {
                        // Request ARCore installation or update if needed.
                        ArCoreApk.InstallStatus installStatus =
                                ArCoreApk.getInstance().requestInstall(this, /*userRequestedInstall=*/ true);
                        switch (installStatus) {
                            case INSTALL_REQUESTED:
                                Log.e(TAG, "ARCore installation requested.");
                                return false;
                            case INSTALLED:
                                break;
                        }
                    } catch (UnavailableException e) {
                        Log.e(TAG, "ARCore not installed", e);
                        runOnUiThread(
                                () ->
                                        Toast.makeText(
                                                getApplicationContext(), "ARCore not installed\n" + e, Toast.LENGTH_LONG)
                                                .show());
                        finish();
                        return false;
                    }
                    break;
                case UNKNOWN_ERROR:
                case UNKNOWN_CHECKING:
                case UNKNOWN_TIMED_OUT:
                case UNSUPPORTED_DEVICE_NOT_CAPABLE:
                    Log.e(
                            TAG,
                            "ARCore is not supported on this device, ArCoreApk.checkAvailability() returned "
                                    + availability);
                    runOnUiThread(
                            () ->
                                    Toast.makeText(
                                            getApplicationContext(),
                                            "ARCore is not supported on this device, "
                                                    + "ArCoreApk.checkAvailability() returned "
                                                    + availability,
                                            Toast.LENGTH_LONG)
                                            .show());
                    return false;
            }
            return true;
        }


        public ArrayList<String> getResolutions (Context context, String cameraId,int imageFormat){
            Log.v(TAG + "getResolutions:", " cameraId:" + cameraId + " imageFormat: " + imageFormat);

            ArrayList<String> output = new ArrayList<>();
            try {
                CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

                for (Size s : characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(imageFormat)) {
                    double aspect = (double) s.getWidth() / (double) s.getHeight();
                    double screen_aspect = (double) surfaceView.getWidth() / (double) surfaceView.getHeight();
                    output.add(s.getWidth() + "x" + s.getHeight() + " aspect: " + aspect + " screen aspect: " + screen_aspect);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return output;
        }


        public void initCamera (Context context, String cameraId,int index){
            boolean ok = false;
            try {
                int current = 0;
                CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                for (Size s : characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.DEPTH16)) {
                    ok = true;
                    if (current == index)
                        break;
                    else ;
                    current++;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            if (!ok) {
                Log.e(TAG + " initCamera", "Depth sensor not found!");
            }
        }

    }







