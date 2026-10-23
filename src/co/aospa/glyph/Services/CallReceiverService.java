/*
 * Copyright (C) 2022-2024 Paranoid Android
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

package co.aospa.glyph.Services;

import android.content.Intent;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.ContactsContract;
import android.telecom.Call;
import android.telecom.InCallService;
import android.telecom.PhoneAccountHandle;
import android.util.Log;

import co.aospa.glyph.Manager.AnimationManager;
import co.aospa.glyph.Manager.SettingsManager;
import co.aospa.glyph.Constants.Constants;
import co.aospa.glyph.Utils.ResourceUtils;

import co.aospa.glyph.Manager.StatusManager;
import co.aospa.glyph.Utils.AnimationUtils;

public class CallReceiverService extends InCallService {

    private static final String TAG = "GlyphCallReceiverService";
    private static final boolean DEBUG = true;

    private AudioManager mAudioManager;

    private HandlerThread thread;
    private Handler mThreadHandler;

    private int contactId = 0;
    private String callingPkg = null;
    private volatile boolean mIsRinging = false;

    private final Runnable playCall = new Runnable() {
        @Override
        public void run() {
            if (!mIsRinging) return;
            if (contactId != 0
                    && SettingsManager.contactHasGlyphCallConfig(contactId)) {
                AnimationManager.playCall(
                        SettingsManager.getGlyphCallAnimation(contactId),
                        SettingsManager.isGlyphCallAnimationReversed(contactId)
                );
            } else if (callingPkg != null && SettingsManager.appHasGlyphCallConfig(callingPkg)) {
                if (SettingsManager.isGlyphCallEnabled(callingPkg)) {
                    AnimationManager.playCall(
                            SettingsManager.getGlyphCallAnimation(callingPkg),
                            SettingsManager.isGlyphCallAnimationReversed(callingPkg)
                    );
                }
            } else if (SettingsManager.isGlyphRingtoneSyncEnabled()
                    && AnimationUtils.Holder.Call.isAvailable()) {
                AnimationManager.playCallSynced(
                        AnimationUtils.Holder.Call.getFrames(),
                        SettingsManager.isGlyphCallAnimationReversed()
                );
            } else {
                AnimationManager.playCall(
                        SettingsManager.getGlyphCallAnimation(),
                        SettingsManager.isGlyphCallAnimationReversed()
                );
            }
        }
    };

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);

        if (call.getDetails() != null && call.getDetails().getState() == Call.STATE_RINGING) {
            handleRingingCall(call);
        }

        call.registerCallback(new Call.Callback() {
            @Override
            public void onStateChanged(Call call, int state) {
                switch (state) {
                    case Call.STATE_RINGING:
                        handleRingingCall(call);
                        break;
                    case Call.STATE_ACTIVE, Call.STATE_DISCONNECTED:
                        disableCallAnimation();
                        break;
                }
            }
        });
    }

    private synchronized void handleRingingCall(Call call) {
        if (mIsRinging) return;
        mIsRinging = true;
        StatusManager.setCallLedEnabled(true);
        getCallingPkg(call);
        final Call.Details details = call.getDetails();
        mThreadHandler.post(() -> {
            if (!mIsRinging) return;
            if (details != null) {
                tryContactId(details);
            }
            if (!mIsRinging) return;
            playCall.run();
        });
    }

    @Override
    public void onCreate() {

        if (DEBUG) Log.d(TAG, "Creating service");

        if (Constants.CONTEXT == null) {
            Constants.CONTEXT = getApplicationContext();
        }

        thread = new HandlerThread("CallReceiverService");
        thread.start();
        Looper looper = thread.getLooper();
        mThreadHandler = new Handler(looper);

        mAudioManager = getSystemService(AudioManager.class);
        mAudioManager.addOnModeChangedListener(cmd ->
                mThreadHandler.post(cmd), mAudioManagerOnModeChangedListener);
        mAudioManagerOnModeChangedListener.onModeChanged(mAudioManager.getMode());
    }

    @Override
    public IBinder onBind(Intent intent) {

        if (Constants.CONTEXT == null) {
            Constants.CONTEXT = getApplicationContext(); 
        } 
        if (!SettingsManager.isGlyphCallEnabled()) {
            return null;
        }

        return super.onBind(intent);
    }

    @Override
    public void onCallRemoved(Call call) {
        super.onCallRemoved(call);
        disableCallAnimation();
    }

    @Override
    public void onDestroy() {
        if (DEBUG) Log.d(TAG, "Destroying service");
        mAudioManager.removeOnModeChangedListener(mAudioManagerOnModeChangedListener);
        disableCallAnimation();
        thread.quitSafely();
        super.onDestroy();
    }

    private void getCallingPkg(Call call) {
        if (call.getDetails() == null) return;
        PhoneAccountHandle handle = call.getDetails().getAccountHandle();
        if (handle != null && handle.getComponentName() != null) {
            callingPkg = handle.getComponentName().getPackageName();
            Log.d("TAG", "Call from package: " + callingPkg);
        }
    }

    private void tryContactId(Call.Details details) {
        Uri handle = details.getHandle();
        String number = "";
        if (handle == null) return;

        String scheme = handle.getScheme();

        if ("tel".equals(scheme)) {
            number = handle.getSchemeSpecificPart();
        }

        if (number == null || number.isEmpty()) return;

        String id = ResourceUtils.getContactIdForNumber(number);
        if (id != null) {
            contactId = Integer.parseInt(id);
        } else {
            contactId = 0;
        }

    }

    private synchronized void disableCallAnimation() {
        if (DEBUG) Log.d(TAG, "disableCallAnimation");
        mIsRinging = false;
        mThreadHandler.removeCallbacksAndMessages(null);
        contactId = 0;
        callingPkg = null;
        AnimationManager.stopCall();
    }

    private final AudioManager.OnModeChangedListener mAudioManagerOnModeChangedListener
            = mode -> {
                if (mode != AudioManager.MODE_RINGTONE) {
                    if (DEBUG) Log.d(TAG, "mAudioManagerOnModeChangedListener: " + mode);
                    disableCallAnimation();
                }
            };
}
