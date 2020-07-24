package com.cosxmlreactnative;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.tencent.qcloud.core.auth.BasicLifecycleCredentialProvider;
import com.tencent.qcloud.core.auth.QCloudLifecycleCredentials;
import com.tencent.qcloud.core.auth.SessionQCloudCredentials;
import com.tencent.qcloud.core.common.QCloudClientException;

/**
 * <p>
 * </p>
 * Created by wjielai on 2020/7/22.
 * Copyright 2010-2020 Tencent Cloud. All Rights Reserved.
 */

class BridgeCredentialProvider extends BasicLifecycleCredentialProvider {

    private final ReactApplicationContext reactContext;

    private final Object sessionWaitingLock = new Object();
    private SessionQCloudCredentials newCredentials;

    BridgeCredentialProvider(ReactApplicationContext reactContext) {
        super();
        this.reactContext = reactContext;
    }

    public void setNewCredentials(SessionQCloudCredentials newCredentials) {
        this.newCredentials = newCredentials;

        synchronized (sessionWaitingLock) {
            sessionWaitingLock.notify();
        }
    }

    @Override
    protected QCloudLifecycleCredentials fetchNewCredentials() throws QCloudClientException {
        sendUpdateCredentialMessage();

        synchronized (sessionWaitingLock) {
            try {
                sessionWaitingLock.wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        return newCredentials;
    }

    private void sendUpdateCredentialMessage() {
        WritableMap params = Arguments.createMap();
        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit("COSUpdateSessionCredential", params);
    }
}
