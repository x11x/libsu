/*
 * Copyright 2020 John "topjohnwu" Wu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.topjohnwu.superuser.ipc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Debug;
import android.os.FileObserver;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;

import androidx.annotation.Nullable;

import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.internal.IRootIPC;
import com.topjohnwu.superuser.internal.UiThreadHandler;
import com.topjohnwu.superuser.internal.Utils;

import java.io.File;
import java.lang.reflect.Constructor;

import static android.os.FileObserver.CREATE;
import static android.os.FileObserver.DELETE;
import static android.os.FileObserver.DELETE_SELF;
import static android.os.FileObserver.MODIFY;
import static android.os.FileObserver.MOVED_FROM;
import static android.os.FileObserver.MOVED_TO;
import static com.topjohnwu.superuser.internal.IPCMain.getServiceName;
import static com.topjohnwu.superuser.ipc.IPCClient.INTENT_DEBUG_KEY;
import static com.topjohnwu.superuser.ipc.IPCClient.INTENT_LOGGING_KEY;
import static com.topjohnwu.superuser.ipc.RootService.TAG;

class IPCServer extends IRootIPC.Stub implements IBinder.DeathRecipient {

    private final ComponentName mName;
    private final RootService service;

    @SuppressWarnings("FieldCanBeLocal")
    private final AppObserver observer;  /* A strong reference is required */

    private IBinder mClient;
    private Intent mIntent;

    @SuppressWarnings("unchecked")
    IPCServer(Context context, ComponentName name) throws Exception {
        IBinder binder = HiddenAPIs.getService(getServiceName(name));
        if (binder != null) {
            // There was already a root service running
            IRootIPC ipc = IRootIPC.Stub.asInterface(binder);
            try {
                // Trigger re-broadcast
                ipc.broadcast();

                // Our work is done!
                System.exit(0);
            } catch (RemoteException e) {
                // Daemon dead, continue
            }
        }

        mName = name;
        Class<RootService> clz = (Class<RootService>) Class.forName(name.getClassName());
        Constructor<RootService> constructor = clz.getDeclaredConstructor();
        constructor.setAccessible(true);
        service = constructor.newInstance();
        service.attach(context, this);
        service.onCreate();
        observer = createObserver();
        observer.startWatching();

        broadcast();

        // Start main thread looper
        Looper.loop();
    }

    // Monitor ANY modify event to the APK
    AppObserver createObserver() {
        File apk = new File(service.getPackageCodePath());
        if (apk.getParent().equals("/data/app")) {
            // No subfolder, directly monitor the APK itself
            return new AppObserver(apk.getPath(), DELETE_SELF|MODIFY);
        } else {
            // APK in subfolder, monitor the folder
            return new AppObserver(apk.getParent(), CREATE|DELETE|DELETE_SELF|MOVED_TO|MOVED_FROM);
        }
    }

    class AppObserver extends FileObserver {

        AppObserver(String path, int flags) {
            super(path, flags);
            Utils.log(TAG, "Start monitoring: " + path);
        }

        @Override
        public void onEvent(int event, @Nullable String path) {
            UiThreadHandler.run(() -> {
                Utils.log(TAG, "AppObserver event: " + event);
                stop();
            });
        }
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        // Small trick for stopping the service without going through AIDL
        if (code == LAST_CALL_TRANSACTION - 1) {
            stop();
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }

    @Override
    public void broadcast() {
        Intent broadcast = IPCClient.getBroadcastIntent(mName, this);
        broadcast.addFlags(HiddenAPIs.FLAG_RECEIVER_FROM_SHELL());
        service.sendBroadcast(broadcast);
    }

    @Override
    public synchronized IBinder bind(Intent intent, IBinder client) {
        // ComponentName doesn't match, abort
        if (!mName.equals(intent.getComponent()))
            System.exit(1);

        Shell.Config.verboseLogging(intent.getBooleanExtra(INTENT_LOGGING_KEY, false));

        Utils.log(TAG, mName + " bind");

        if (intent.getBooleanExtra(INTENT_DEBUG_KEY, false)) {
            // ActivityThread.attach(true, 0) will set this to system_process
            HiddenAPIs.setAppName(service.getPackageName() + ":root");
            // For some reason Debug.waitForDebugger() won't work, manual spin lock...
            while (!Debug.isDebuggerConnected()) {
                try { Thread.sleep(200); }
                catch (InterruptedException ignored) {}
            }
        }

        try {
            mClient = client;
            client.linkToDeath(this, 0);

            class Container { IBinder obj; }
            Container binderContainer = new Container();
            UiThreadHandler.runAndWait(() -> {
                if (mIntent != null)
                    service.onRebind(intent);
                else
                    mIntent = intent.cloneFilter();
                binderContainer.obj = service.onBind(intent);
            });
            return binderContainer.obj;
        } catch (Exception e) {
            Utils.err(e);
            return null;
        }
    }

    @Override
    public synchronized void unbind() {
        Utils.log(TAG, mName + " unbind");
        mClient.unlinkToDeath(this, 0);
        mClient = null;
        UiThreadHandler.run(() -> {
            if (!service.onUnbind(mIntent)) {
                service.onDestroy();
                System.exit(0);
            } else {
                // Register ourselves as system service
                HiddenAPIs.addService(getServiceName(mName), this);
            }
        });
    }

    @Override
    public synchronized void stop() {
        Utils.log(TAG, mName + " stop");
        if (mClient != null) {
            mClient.unlinkToDeath(this, 0);
            mClient = null;
        }
        UiThreadHandler.run(() -> {
            service.onDestroy();
            System.exit(0);
        });
    }

    @Override
    public void binderDied() {
        Utils.log(TAG, mName + " binderDied");
        unbind();
    }
}
