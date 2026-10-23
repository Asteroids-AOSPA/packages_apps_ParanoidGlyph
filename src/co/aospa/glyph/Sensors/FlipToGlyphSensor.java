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

package co.aospa.glyph.Sensors;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.util.Log;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

public class FlipToGlyphSensor implements SensorEventListener {

    private static final boolean DEBUG = true;
    private static final String TAG = "FlipToGlyphSensor";

    private boolean isFlipped = false;
    private final Consumer<Boolean> mOnFlip;

    private SensorManager mSensorManager;
    private Sensor mSensorAccelerometer;
    private Context mContext;
    private Handler mHandler;

    private Duration mTimeThreshold = Duration.ofMillis(700L);
    private float mMovementThreshold = 0.8f;
    private float mZAccelerationThreshold = -8.0f;
    private float mZAccelerationThresholdLenient = -6.5f;

    private float mPrevX = 0f;
    private float mPrevY = 0f;
    private float mPrevZ = 0f;
    private long mPrevAccelerationTime = 0L;
    private boolean mZAccelerationIsFaceDown = false;
    private long mZAccelerationFaceDownTime = 0L;
    private boolean mFirstSample = true;

    private static final float MOVING_AVERAGE_WEIGHT = 0.5f;
    private final ExponentialMovingAverage mCurrentZAcceleration =
            new ExponentialMovingAverage(MOVING_AVERAGE_WEIGHT);

    public FlipToGlyphSensor(Context context, @NonNull Consumer<Boolean> onFlip) {
        this(context, null, onFlip);
    }

    public FlipToGlyphSensor(Context context, Handler handler, @NonNull Consumer<Boolean> onFlip) {
        mContext = context;
        mHandler = handler;
        mOnFlip = Objects.requireNonNull(onFlip);
        mSensorManager = mContext.getSystemService(SensorManager.class);
        mSensorAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true);
        if (mSensorAccelerometer == null) {
            Log.w(TAG, "Wakeup accelerometer not found, falling back to non-wakeup");
            mSensorAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, false);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;

        final float x = event.values[0];
        final float y = event.values[1];
        final float z = event.values[2];
        final long curTime = event.timestamp;

        if (mFirstSample) {
            mCurrentZAcceleration.reset(z);
            mPrevX = x;
            mPrevY = y;
            mPrevZ = z;
            mPrevAccelerationTime = curTime;
            mFirstSample = false;
            return;
        }

        mCurrentZAcceleration.updateMovingAverage(z);

        final float deltaX = Math.abs(x - mPrevX);
        final float deltaY = Math.abs(y - mPrevY);
        final float deltaZ = Math.abs(z - mPrevZ);
        final float totalDelta = deltaX + deltaY + deltaZ;

        mPrevX = x;
        mPrevY = y;
        mPrevZ = z;

        if (totalDelta > mMovementThreshold) {
            mPrevAccelerationTime = curTime;
        }
        final boolean moving = (curTime - mPrevAccelerationTime) <= mTimeThreshold.toNanos();

        final float zAccelerationThreshold =
                isFlipped ? mZAccelerationThresholdLenient : mZAccelerationThreshold;
        final boolean isCurrentlyFaceDown =
                mCurrentZAcceleration.mMovingAverage < zAccelerationThreshold;
        final boolean isFaceDownForPeriod = isCurrentlyFaceDown
                && mZAccelerationIsFaceDown
                && (curTime - mZAccelerationFaceDownTime) > mTimeThreshold.toNanos();

        if (isCurrentlyFaceDown && !mZAccelerationIsFaceDown) {
            mZAccelerationFaceDownTime = curTime;
            mZAccelerationIsFaceDown = true;
        } else if (!isCurrentlyFaceDown) {
            mZAccelerationIsFaceDown = false;
        }

        if (!moving && isFaceDownForPeriod && !isFlipped) {
            onFlip(true);
        } else if (!isFaceDownForPeriod && isFlipped) {
            onFlip(false);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void onFlip(boolean flipped) {
        if (DEBUG) Log.d(TAG, "Flipped: " + flipped);
        mOnFlip.accept(flipped);
        isFlipped = flipped;
    }

    public void enable() {
        if (DEBUG) Log.d(TAG, "Enabling Sensor");
        if (mSensorAccelerometer == null) {
            Log.e(TAG, "Cannot enable: No accelerometer sensor available");
            return;
        }
        mFirstSample = true;
        isFlipped = false;
        mZAccelerationIsFaceDown = false;
        mPrevAccelerationTime = 0L;
        mZAccelerationFaceDownTime = 0L;
        mSensorManager.unregisterListener(this, mSensorAccelerometer);
        if (mHandler != null) {
            mSensorManager.registerListener(this, mSensorAccelerometer,
                    SensorManager.SENSOR_DELAY_NORMAL, 0, mHandler);
        } else {
            mSensorManager.registerListener(this, mSensorAccelerometer,
                    SensorManager.SENSOR_DELAY_NORMAL, 0);
        }
    }

    public void disable() {
        if (DEBUG) Log.d(TAG, "Disabling Sensor");
        onFlip(false);
        mSensorManager.unregisterListener(this, mSensorAccelerometer);
    }

    private final class ExponentialMovingAverage {
        private final float mAlpha;
        private final float mInitialAverage;
        private float mMovingAverage;

        ExponentialMovingAverage(float alpha) {
            this(alpha, 0.0f);
        }

        ExponentialMovingAverage(float alpha, float initialAverage) {
            this.mAlpha = alpha;
            this.mInitialAverage = initialAverage;
            this.mMovingAverage = initialAverage;
        }

        void updateMovingAverage(float newValue) {
            mMovingAverage = newValue + mAlpha * (mMovingAverage - newValue);
        }

        void reset() {
            mMovingAverage = this.mInitialAverage;
        }

        void reset(float initialAverage) {
            mMovingAverage = initialAverage;
        }
    }
}