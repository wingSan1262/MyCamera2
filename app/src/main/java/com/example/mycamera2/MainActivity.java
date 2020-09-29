
package com.example.mycamera2;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.RelativeLayout;
import android.widget.Switch;
import android.widget.Toast;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.ChecksumException;
import com.google.zxing.FormatException;
import com.google.zxing.LuminanceSource;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static android.hardware.camera2.CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN;
import static android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE;

public class MainActivity extends AppCompatActivity {
    // image format recommended for scanning QR code
    private static final int mImageFormat = ImageFormat.YUV_420_888;
    private static final int CONTINUE_PREVIEW = 10;
    private static final int CAPTURE_QR_SCAN = 11;

    // listener for texture view( wing )
    TextureView.SurfaceTextureListener mSurfaceTextureListener;

    // texture view object from layout for registering listener( wing )
    TextureView mTextureView;

    //save the preview size of camera hardware ( wing )
    Size previewSize;

    // object to get camera service, camera id available( wing )
    CameraManager mCameraManager;

    // camera Id obtained holder from camera manager infrormation( wing )
    private String cameraId;

    // state callback object for opening camera ( wing )
    private CameraDevice.StateCallback mCameraDeviceStateCallback;

    //this is for requesting a CaptureRequestBuilder for setRepeatingRequest
    private CameraDevice mCameraDeviceObtained;

    // instance builder for CaptureRequest which will be passed for capturing
    private CaptureRequest.Builder mPreviewRequestBuilder;
    private CaptureRequest mPreviewRequest;

    private ZoomClass zoomUtils;

    private ImageReader mImageReader;
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;

    //camera Handler, currently not used cause i have no idea how to use camera2API on a Handler
    private Handler mCameraHandler;

    // this is ImageReader Listener since camera 2 API do not provide a direct image stream,
    // the data stream is processed through ImageReader
    private ImageReader.OnImageAvailableListener mOnImageAvailableListener;
    private String TAG = "MAIN ACTIVITY";
    private CameraCaptureSession mCaptureSession;
    private boolean stopRepeatingFlag = false;

    private String mQrObtained = null;

    private RelativeLayout cameraPreviewTouch;
    private boolean cameraReady = false;

    private Button plusZoom;
    private Button minusZoom;
    private float currentZoom = 0.0f;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mTextureView = findViewById(R.id.textue_view);
        plusZoom = findViewById(R.id.plus_zoom);
        minusZoom = findViewById(R.id.minus_zoom);

        // this handler is not used yet please ignore IGNORE
        mCameraHandler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case CONTINUE_PREVIEW : {
                        if (mCaptureSession != null && !stopRepeatingFlag) {
                            try {
                                stopRepeatingFlag = true;
                                mCaptureSession.stopRepeating();
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                                Log.e("CAMERA", "abort capture failed");
                            }
                        }
                        createPreviewSession();
                        break;
                    }
                    case CAPTURE_QR_SCAN : {
                    }
                }
            }
        };

        // this is code if you want to manually capture using button, more less like this
//        cameraPreviewTouch = findViewById(R.id.camera_preview_touch);
//        cameraPreviewTouch.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View v) {
//                if (cameraReady) {
//                    takePicture();
//                }
//            }
//        });
        mCameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_DENIED){
            ActivityCompat.requestPermissions(this, new String[] {Manifest.permission.CAMERA}, 100);
        } else {
            startBackgroundThread();
            // setting up all the components for listener and preview callback
            setUpLayoutsAndListeners();
        }


    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == 100) {
            for (int i = 0; i < permissions.length; i++) {
                String permission = permissions[i];
                int grantResult = grantResults[i];

                if (permission.equals(Manifest.permission.CAMERA)) {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        setUpLayoutsAndListeners();
                    } else {
                        ActivityCompat.requestPermissions(this, new String[] {Manifest.permission.CAMERA}, 100);
                    }
                }
            }
        }
    }

    private void setUpLayoutsAndListeners() {


        mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                getCameraId();
                openCamera();
                try {
                    zoomUtils = new ZoomClass(mCameraManager.getCameraCharacteristics(cameraId));
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }
            }
            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
            }
            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                return false;
                // please add memory leaks handling later, i am too lazy
            }
            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
                // please add memory leaks handling later, i am too lazy
            }
        };

        mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);

        mCameraDeviceStateCallback = new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice cameraDevice) {
                mCameraDeviceObtained = cameraDevice;
                createPreviewSession();
                plusZoom.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // zoom experiment
                        if (currentZoom < zoomUtils.maxZoom){
                            currentZoom = currentZoom + 1f;
                            zoomUtils.setZoom(mPreviewRequestBuilder, currentZoom);
                            mPreviewRequest = mPreviewRequestBuilder.build();
                            try {
                                mCaptureSession.setRepeatingRequest(mPreviewRequest, null,
                                        mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                });

                minusZoom.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // zoom experiment
                        if (currentZoom >= 1f && currentZoom != 0){
                            currentZoom = currentZoom - 1f;
                            zoomUtils.setZoom(mPreviewRequestBuilder, currentZoom);
                            mPreviewRequest = mPreviewRequestBuilder.build();
                            try {
                                mCaptureSession.setRepeatingRequest(mPreviewRequest, null,
                                        mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                });
                cameraReady = true;
                //TODO something lack here !!! memory leak prevention!!
            }

            @Override
            public void onDisconnected(CameraDevice cameraDevice) {
                mCameraDeviceObtained = null;
            }

            @Override
            public void onError(CameraDevice cameraDevice, int error) {
                mCameraDeviceObtained = null;
            }
        };

        mOnImageAvailableListener =
            new ImageReader.OnImageAvailableListener() {
                Image img;
                Result rawResult = null;
                QRCodeReader mQrReader = new QRCodeReader();
                String qrString = null;
                @Override
                public void onImageAvailable(ImageReader reader) {
                    try {
                        img = reader.acquireLatestImage();
                        if(img == null){
                            finish();
                        }
                        ByteBuffer buffer = img.getPlanes()[0].getBuffer();
                        byte[] data = new byte[buffer.remaining()];
                        buffer.get(data);
                        int width = img.getWidth();
                        int height = img.getHeight();
                        PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(data, width, height);
                        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

                        rawResult = mQrReader.decode(bitmap);
                        qrString = rawResult.getText();
                        if (qrString == null) {
                            Toast.makeText(getApplicationContext(), "not obtained", Toast.LENGTH_LONG).show();
                        } else if (mQrObtained == null || !mQrObtained.equals(qrString)){
                            mQrObtained = qrString;
                            Toast.makeText(getApplicationContext(), qrString, Toast.LENGTH_LONG).show();
                        }
                    } catch (ReaderException ignored) {
                        Log.e(TAG, "Reader shows an exception! ", ignored);
                        /* Ignored */
                    } catch (NullPointerException ex) {
                        ex.printStackTrace();
                    } finally {
                        mQrReader.reset();
                        Log.e(TAG, "in the finally! ------------");
                        if (img != null)
                            img.close();

                    }
                    if (rawResult != null) {
                        Log.e(TAG, "Decoding successful!");
                    } else {
                        Log.d(TAG, "No QR code foundâ€¦");
                    }
                    stopRepeatingFlag = false;
//                    mCameraHandler.sendEmptyMessage(CONTINUE_PREVIEW);
                }
            };
    }

    private void getCameraId() {
        try {
            for (String id : mCameraManager.getCameraIdList()){
                CameraCharacteristics localCameraCharacteristic = mCameraManager.getCameraCharacteristics(id);
                if (localCameraCharacteristic == null) {
                    finish();
                }
                if (localCameraCharacteristic.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    // get characteristic output capability of camera ID, or so i thought ???
                    StreamConfigurationMap streamConfigurationMap = localCameraCharacteristic.get(
                            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    previewSize = streamConfigurationMap.getOutputSizes(SurfaceTexture.class)[0];
                    this.cameraId = id;
                }
            }
        } catch (CameraAccessException e){
            e.printStackTrace();
        }

    }

    @SuppressLint("MissingPermission")
    private void openCamera(){
        try {
            mCameraManager.openCamera(this.cameraId, mCameraDeviceStateCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * this fucker is for drawing the camera buffer into holder you want, texture view, ImageReader
     * any fucker that will compatible, you call it.
     *
     * this method contain the procedure for capturing using camera 2
     * please pay attention to size used for the surface target and the camera preview size
     * set and or obtained, you may copy this flow for drawing preview or capture some thing
     */
    private void createPreviewSession() {
        // TODO :this is my attempt to lighten the performance burden by reduce the size of the ImageReader
        // TODO :but this causing the Zxing aar fail to read the QR code, please anyone.
//        CameraCharacteristics localCameraCharacteristic = null;
//        Size[] streamConfigurationMap = null;
//        Size imageSurfaceSize = null ;
//        try {
//            localCameraCharacteristic = mCameraManager.getCameraCharacteristics(cameraId);
//        } catch (CameraAccessException e) {
//            e.printStackTrace();
//        }
//
//        if(localCameraCharacteristic != null){
//            streamConfigurationMap = localCameraCharacteristic.get(
//                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(mImageFormat);
//            for (Size iterationSize : streamConfigurationMap){
//                if((iterationSize.getHeight()/iterationSize.getWidth() == previewSize.getHeight()/previewSize.getWidth())
//                        && (iterationSize.getHeight() < previewSize.getHeight())){
//                    imageSurfaceSize = iterationSize;
//                    break;
//                }
//            }
//        }

//        assert imageSurfaceSize != null;

        // first, the Image Reader for obtain image
        mImageReader = ImageReader.newInstance(previewSize.getWidth(),
                previewSize.getHeight(),
                mImageFormat, 1);
        if (mBackgroundHandler != null && mOnImageAvailableListener != null) {
            mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundHandler);
        }

        // preview surface drawing
        SurfaceTexture mPreviewTexture = mTextureView.getSurfaceTexture();
        if (mPreviewTexture != null) {
            mPreviewTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        }

        // getting a surface object for the drawing process
        Surface mPreviewSurface = new Surface(mPreviewTexture);
        Surface mImageSurface = mImageReader.getSurface();

        if (mCameraDeviceObtained != null) {
            try {
                mPreviewRequestBuilder = mCameraDeviceObtained.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        mPreviewRequestBuilder.addTarget(mImageSurface);
        mPreviewRequestBuilder.addTarget(mPreviewSurface);


        //TODO Here, we create a CameraCaptureSession for camera preview this may cause bug
        //todo poorly programed -> laggy preview
        try {
            // add image reader surface for preview capture to image
            mCameraDeviceObtained.createCaptureSession(Arrays.asList(mPreviewSurface, mImageSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession cameraCaptureSession) {
                            // The camera is already closed
                            Log.e(TAG, "onConfigured");
                            if (mCameraDeviceObtained == null) return;

                            // When the session is ready, we start displaying the preview.
                            mCaptureSession = cameraCaptureSession;
                            try {
                                // Auto focus should be continuous for camera preview.
                                mPreviewRequestBuilder.set(CONTROL_AF_MODE, CONTROL_AF_STATE_ACTIVE_SCAN);
                                // Finally, we start displaying the camera preview.
                                mPreviewRequest = mPreviewRequestBuilder.build();
                                mCaptureSession.setRepeatingRequest(mPreviewRequest, null,
                                        mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
                        }
                    }, null
            );
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
//        mCameraHandler.sendEmptyMessageDelayed(CAPTURE_QR_SCAN, 5000);
    }



    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    //TODO fix this motherfu*ker
    // this method is used for taking picture, but i still failed on capturing without freezing the preview for a moment
    // call the preview method may continue the preview again, but the preview looks lagging in a moment
    private void takePicture() {
        // first, the Image Reader for obtain image
        mImageReader = ImageReader.newInstance(previewSize.getWidth(),
                previewSize.getHeight(),
                mImageFormat, 2);
        if (mBackgroundHandler != null && mOnImageAvailableListener != null) {
            mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundHandler);
        }

        // preview surface drawing
        SurfaceTexture mPreviewTexture = mTextureView.getSurfaceTexture();
        if (mPreviewTexture != null) {
            mPreviewTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        }

        Surface mPreviewSurface = new Surface(mPreviewTexture);
        Surface mImageSurface = mImageReader.getSurface();

        if (mCameraDeviceObtained != null) {
            try {
                mPreviewRequestBuilder = mCameraDeviceObtained.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        mPreviewRequestBuilder.addTarget(mImageSurface);
        mPreviewRequestBuilder.addTarget(mPreviewSurface);

        //TODO Here, we create a CameraCaptureSession for camera preview this may cause bug
        //todo poorly programed
        try {
            // add image reader surface for preview capture to image
            mCameraDeviceObtained.createCaptureSession(Arrays.asList(mImageSurface, mPreviewSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession cameraCaptureSession) {
                            // The camera is already closed
                            Log.e(TAG, "onConfigured");
                            if (mCameraDeviceObtained == null) return;

                            // When the session is ready, we start displaying the preview.
                            mCaptureSession = cameraCaptureSession;
                            try {
                                // Auto focus should be continuous for camera preview.
                                mPreviewRequestBuilder.set(CONTROL_AF_MODE, CONTROL_AF_STATE_ACTIVE_SCAN);
                                // Finally, we start displaying the camera preview.
                                mPreviewRequest = mPreviewRequestBuilder.build();
                                mCaptureSession.setRepeatingRequest(mPreviewRequest, null,
                                        mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
                        }
                    }, null
            );
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

}
