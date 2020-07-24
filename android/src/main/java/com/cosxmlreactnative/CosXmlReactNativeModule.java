
package com.cosxmlreactnative;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.tencent.cos.xml.CosXmlServiceConfig;
import com.tencent.cos.xml.CosXmlSimpleService;
import com.tencent.cos.xml.exception.CosXmlClientException;
import com.tencent.cos.xml.exception.CosXmlServiceException;
import com.tencent.cos.xml.listener.CosXmlProgressListener;
import com.tencent.cos.xml.listener.CosXmlResultListener;
import com.tencent.cos.xml.model.CosXmlRequest;
import com.tencent.cos.xml.model.CosXmlResult;
import com.tencent.cos.xml.model.object.HeadObjectRequest;
import com.tencent.cos.xml.transfer.COSXMLDownloadTask;
import com.tencent.cos.xml.transfer.COSXMLUploadTask;
import com.tencent.cos.xml.transfer.TransferConfig;
import com.tencent.cos.xml.transfer.TransferManager;
import com.tencent.qcloud.core.auth.QCloudCredentialProvider;
import com.tencent.qcloud.core.auth.SessionQCloudCredentials;
import com.tencent.qcloud.core.auth.ShortTimeCredentialProvider;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * <p>
 * </p>
 * Created by wjielai on 2020/7/22.
 * Copyright 2010-2020 Tencent Cloud. All Rights Reserved.
 */
public class CosXmlReactNativeModule extends ReactContextBaseJavaModule {

    private final ReactApplicationContext reactContext;

    private CosXmlSimpleService cosXmlService;
    private CosXmlServiceConfig serviceConfig;
    private TransferManager transferManager;
    private BridgeCredentialProvider bridgeCredentialProvider;

    private Map<String, COSXMLUploadTask> uploadTasks;
    private Map<String, String> uploadIdMap;

    public CosXmlReactNativeModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
    }

    @Override
    @NonNull
    public String getName() {
        return "CosXmlReactNative";
    }

    @Nullable
    @Override
    public Map<String, Object> getConstants() {
        return super.getConstants();
    }

    @ReactMethod
    public void initWithPlainSecret(ReadableMap configuration, ReadableMap credentials) {
        serviceConfig = initConfiguration(configuration);
        QCloudCredentialProvider credentialProvider = new ShortTimeCredentialProvider(
                safeGetString(credentials, "secretId"),
                safeGetString(credentials, "secretKey"),
                600
        );

        cosXmlService = new CosXmlSimpleService(reactContext, serviceConfig,
                credentialProvider);
        transferManager = new TransferManager(cosXmlService, new TransferConfig.Builder().build());

        uploadTasks = new HashMap<>();
        uploadIdMap = new HashMap<>();
    }

    @ReactMethod
    public void initWithSessionCredentialCallback(ReadableMap configuration) {
        serviceConfig = initConfiguration(configuration);
        bridgeCredentialProvider = new BridgeCredentialProvider(reactContext);

        cosXmlService = new CosXmlSimpleService(reactContext, serviceConfig,
                bridgeCredentialProvider);
        transferManager = new TransferManager(cosXmlService, new TransferConfig.Builder().build());

        uploadTasks = new HashMap<>();
        uploadIdMap = new HashMap<>();
    }

    @ReactMethod
    public void updateSessionCredential(ReadableMap credentials) {
        if (bridgeCredentialProvider != null) {

            bridgeCredentialProvider.setNewCredentials(new SessionQCloudCredentials(
                    safeGetString(credentials, "tmpSecretId"),
                    safeGetString(credentials, "tmpSecretKey"),
                    safeGetString(credentials, "sessionToken"),
                    safeGetInt(credentials, "expiredTime")));
        }
    }

    @ReactMethod
    public void putObject(final ReadableMap options, final Promise promise) {
        String fileUri = safeGetString(options, "fileUri");
        final String bucket = safeGetString(options, "bucket");
        final String object = safeGetString(options, "object");
        final String requestId = safeGetString(options, "requestId");
        String uploadId = null;
        if (uploadIdMap.containsKey(requestId)) {
            uploadId = uploadIdMap.get(requestId);
            uploadIdMap.remove(requestId);
        }

        COSXMLUploadTask cosxmlUploadTask;
        if (fileUri.startsWith("content://")) {
            cosxmlUploadTask = transferManager.upload(bucket, object,
                    Uri.parse(fileUri), uploadId);
        } else {
            if (fileUri.startsWith("file://")) {
               fileUri = fileUri.replace("file://", "");
            }
            cosxmlUploadTask = transferManager.upload(bucket, object,
                    fileUri, uploadId);
        }
        if (requestId != null) {
            uploadTasks.put(requestId, cosxmlUploadTask);
        }

        //设置上传进度回调
        cosxmlUploadTask.setCosXmlProgressListener(new CosXmlProgressListener() {
            @Override
            public void onProgress(long complete, long target) {
                sendProgressMessage(requestId, complete, target);
            }
        });

        //设置返回结果回调
        cosxmlUploadTask.setCosXmlResultListener(new CosXmlResultListener() {
            @Override
            public void onSuccess(CosXmlRequest request, CosXmlResult result) {
                uploadTasks.remove(requestId);

                COSXMLUploadTask.COSXMLUploadTaskResult cOSXMLUploadTaskResult =
                        (COSXMLUploadTask.COSXMLUploadTaskResult) result;

                WritableMap params = Arguments.createMap();
                params.putString("Bucket", bucket);
                params.putString("Key", object);
                params.putString("ETag", cOSXMLUploadTaskResult.eTag);
                params.putString("Location", "https://" + serviceConfig.getRequestHost(bucket,
                        false) + "/" + object);

                promise.resolve(params);
            }

            @Override
            public void onFail(CosXmlRequest request,
                               CosXmlClientException clientException,
                               CosXmlServiceException serviceException) {
                uploadTasks.remove(requestId);
                if (clientException != null) {
                    clientException.printStackTrace();
                    promise.reject(clientException);
                } else {
                    serviceException.printStackTrace();
                    promise.reject(serviceException);
                }
            }
        });
    }

    @ReactMethod
    public void pauseUpload(String requestId, final Promise promise) {
        if (uploadTasks.containsKey(requestId)) {
            COSXMLUploadTask task = uploadTasks.get(requestId);
            if (task.pauseSafely()) {
                uploadTasks.remove(requestId);
                uploadIdMap.put(requestId, task.getUploadId());
                promise.resolve(task.getUploadId());
            } else {
                promise.reject(new CosXmlClientException(-1, "UnSupport pausing"));
            }
        }
    }

    @ReactMethod
    public void getObject(ReadableMap options, final Promise promise) {
        final String bucket = safeGetString(options, "bucket");
        final String object = safeGetString(options, "object");
        final String requestId = safeGetString(options, "requestId");
        String filePath = safeGetString(options, "filePath");
        String saveDir = null;
        String savedFileName;

        if (filePath == null) {
            File cacheDir = reactContext.getExternalCacheDir();
            if (cacheDir == null) {
                cacheDir = reactContext.getCacheDir();
            }
            File downloadDir = new File(cacheDir, "cos_download");
            if (!downloadDir.exists()) {
                downloadDir.mkdirs();
            }
            saveDir = downloadDir.getPath();
            savedFileName = object;
            filePath = new File(saveDir, savedFileName).getPath();
        } else {
            saveDir = new File(filePath).getParent();
            if (!new File(saveDir).exists()) {
                new File(saveDir).mkdirs();
            }
            savedFileName = new File(filePath).getName();
        }
        final String finalFilePath = filePath;

        COSXMLDownloadTask cosxmlDownloadTask =
                transferManager.download(reactContext,
                        bucket, object, saveDir, savedFileName);

        //设置下载进度回调
        cosxmlDownloadTask.setCosXmlProgressListener(new CosXmlProgressListener() {
            @Override
            public void onProgress(long complete, long target) {
                sendProgressMessage(requestId, complete, target);
            }
        });
        //设置返回结果回调
        cosxmlDownloadTask.setCosXmlResultListener(new CosXmlResultListener() {
            @Override
            public void onSuccess(CosXmlRequest request, CosXmlResult result) {
                promise.resolve(finalFilePath);
            }

            @Override
            public void onFail(CosXmlRequest request,
                               CosXmlClientException clientException,
                               CosXmlServiceException serviceException) {
                if (clientException != null) {
                    clientException.printStackTrace();
                    promise.reject(clientException);
                } else {
                    serviceException.printStackTrace();
                    promise.reject(serviceException);
                }
            }
        });
    }

    @ReactMethod
    public void headObject(ReadableMap options, final Promise promise) {
        HeadObjectRequest headObjectRequest = new HeadObjectRequest(
                options.getString("bucket"),
                options.getString("object")
        );
        cosXmlService.headObjectAsync(headObjectRequest, new CosXmlResultListener() {
            @Override
            public void onSuccess(CosXmlRequest request, CosXmlResult result) {
                WritableMap writableMap = Arguments.createMap();
                for (String key : result.headers.keySet()) {
                    writableMap.putString(key, TextUtils.join(",", result.headers.get(key)));
                }
                promise.resolve(writableMap);
            }

            @Override
            public void onFail(CosXmlRequest request, CosXmlClientException exception,
                               CosXmlServiceException serviceException) {
                if (exception != null) {
                    exception.printStackTrace();
                    promise.reject(exception);
                } else {
                    serviceException.printStackTrace();
                    promise.reject(serviceException);
                }
            }
        });
    }

    private CosXmlServiceConfig initConfiguration(ReadableMap configuration) {
        String region = configuration.getString("region");

        return new CosXmlServiceConfig.Builder()
                .setRegion(region)
                .isHttps(true)
                .builder();
    }

    private void sendProgressMessage(String requestId, long completeBytes, long targetBytes) {
        WritableMap params = Arguments.createMap();
        params.putString("requestId", requestId);
        params.putString("processedBytes", "" + completeBytes);
        params.putString("targetBytes", "" + targetBytes);
        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit("COSTransferProgressUpdate", params);
    }

    private String safeGetString(ReadableMap options, String key) {
        try {
            return options.getString(key);
        } catch (Exception e) {
            return null;
        }
    }

    private int safeGetInt(ReadableMap options, String key) {
        try {
            return options.getInt(key);
        } catch (Exception e) {
            return -1;
        }
    }

}
