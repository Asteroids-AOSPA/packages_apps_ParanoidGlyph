/*
 * Copyright (C) 2024-2026 Paranoid Android
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

package co.aospa.glyph.Utils;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Native Glyph Animation Auto-Generator tailored specifically for Nothing Phone (3a) (Asteroids).
 * Implements 60 FPS audio-spectrum analysis and pattern choreography based on
 * Better Nothing Glyph Composer (BNGC) architecture.
 */
public final class AudioGlyphGenerator {

    private static final String TAG = "AudioGlyphGenerator";

    public static final int MODE_BEATS = 0;
    public static final int MODE_SMOOTH = 1;
    public static final int MODE_FREQUENCY = 2;
    public static final int MODE_PATTERN = 3;

    public static final int BUFFER_SIZE = 2048;
    public static final int CHANNELS_PHONE3A = 36;
    public static final int MAX_BRIGHTNESS = 4095;

    /**
     * Nothing Phone (3a) / Asteroids LED zones (36 channels total):
     * - Zone 0: Left Arc (20 LEDs, indices 0..19)
     * - Zone 1: Center Circular Arc (11 LEDs, indices 20..30)
     * - Zone 2: Diagonal Dash (5 LEDs, indices 31..35)
     */
    public static final int[][] PHONE3A_ZONES = new int[][] {
        {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19},
        {20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30},
        {31, 32, 33, 34, 35}
    };

    /**
     * 7-Band Frequency Distribution for Phone (3a) 36-LED matrix (Mode 2)
     */
    public static final int[][] FREQ_BAND_LEDS = new int[][] {
        {0, 1, 2, 3},                         // Sub-bass (20-60 Hz, 4 LEDs)
        {4, 5, 6, 7, 8, 9, 10},               // Bass (60-250 Hz, 7 LEDs)
        {11, 12, 13, 14, 15},                 // Low-mid (250-500 Hz, 5 LEDs)
        {16, 17, 18, 19, 20, 21, 22, 23, 24}, // Mid (500-2000 Hz, 9 LEDs)
        {25, 26, 27, 28, 29},                 // High-mid (2000-4000 Hz, 5 LEDs)
        {30, 31, 32, 33},                     // High (4000-8000 Hz, 4 LEDs)
        {34, 35}                              // Very-high (8000-20000 Hz, 2 LEDs)
    };

    public static final float[][] FREQ_RANGES = new float[][] {
        {20f, 60f},
        {60f, 250f},
        {250f, 500f},
        {500f, 2000f},
        {2000f, 4000f},
        {4000f, 8000f},
        {8000f, 20000f}
    };

    private AudioGlyphGenerator() {}

    /**
     * Generate 36-column 60 FPS Glyph CSV animation for Nothing Phone (3a) from an audio Uri.
     */
    public static String generateForPhone3a(Context context, Uri uri, int mode) throws Exception {
        int[] sampleRateHolder = new int[1];
        float[] channelData = decodeToPcm(context, uri, sampleRateHolder);
        return generateForPhone3a(channelData, sampleRateHolder[0], mode);
    }

    /**
     * Generate 36-column 60 FPS Glyph CSV animation for Nothing Phone (3a) from an audio File.
     */
    public static String generateForPhone3a(File file, int mode) throws Exception {
        int[] sampleRateHolder = new int[1];
        float[] channelData = decodeToPcm(file, sampleRateHolder);
        return generateForPhone3a(channelData, sampleRateHolder[0], mode);
    }

    /**
     * Generate 36-column 60 FPS Glyph CSV animation from decoded PCM float samples.
     */
    public static String generateForPhone3a(float[] channelData, int sampleRate, int mode) {
        if (channelData == null || channelData.length == 0) {
            return "";
        }
        if (sampleRate <= 0) {
            sampleRate = 44100;
        }

        switch (mode) {
            case MODE_BEATS:
                return generateBeatsOrSmooth(channelData, sampleRate, false);
            case MODE_SMOOTH:
                return generateBeatsOrSmooth(channelData, sampleRate, true);
            case MODE_FREQUENCY:
                return generateFrequency(channelData, sampleRate);
            case MODE_PATTERN:
            default:
                return generatePattern(channelData, sampleRate);
        }
    }

    /**
     * Mode 0 & 1: Beats (antiFlicker=false) and Smooth (antiFlicker=true).
     * Computes loudness RMS, 90th percentile sweep triggers, band energy assignment,
     * periodic zone rotation, and optional exponential smoothing.
     */
    private static String generateBeatsOrSmooth(float[] channelData, int sampleRate, boolean antiFlicker) {
        int hopSize = Math.max(1, (int) Math.floor((16.666f / 1000f) * sampleRate));
        int totalFrames = Math.max(1, (channelData.length - BUFFER_SIZE) / hopSize + 1);

        FastFFT fft = new FastFFT(BUFFER_SIZE);
        float[] hannWindow = createHannWindow(BUFFER_SIZE);

        float[] loudnessValues = new float[totalFrames];
        float maxLoudness = 0.0001f;
        for (int f = 0; f < totalFrames; f++) {
            int startIdx = f * hopSize;
            float l = calculateRms(channelData, startIdx, BUFFER_SIZE);
            loudnessValues[f] = l;
            if (l > maxLoudness) maxLoudness = l;
        }

        float[] sortedLoudness = Arrays.copyOf(loudnessValues, totalFrames);
        Arrays.sort(sortedLoudness);
        float threshold90 = sortedLoudness[(int) (totalFrames * 0.90f)];
        float dynamicAmplitudeThreshold = 0.5f * maxLoudness;

        float[] currentBrightness = new float[CHANNELS_PHONE3A];
        float decayFactor = 0.88f;

        StringBuilder csv = new StringBuilder(totalFrames * 150);
        float[] real = new float[BUFFER_SIZE];
        float[] imag = new float[BUFFER_SIZE];
        float[] ampSpectrum = new float[BUFFER_SIZE / 2 + 1];

        int zoneShuffleCounter = 0;
        int shuffleInterval = Math.max(1, (int) (sampleRate / (hopSize * 2.0f))); // ~0.5s
        int[] zoneMapping = new int[]{0, 1, 2};

        for (int f = 0; f < totalFrames; f++) {
            int startIdx = f * hopSize;
            if (f % shuffleInterval == 0) {
                zoneShuffleCounter++;
                zoneMapping[0] = zoneShuffleCounter % 3;
                zoneMapping[1] = (zoneShuffleCounter + 1) % 3;
                zoneMapping[2] = (zoneShuffleCounter + 2) % 3;
            }

            int[] frameBrightness = new int[CHANNELS_PHONE3A];
            float loudness = loudnessValues[f];

            if (loudness > threshold90) {
                // Heavy beat sweep effect
                int direction = ((f / 10) % 2 == 0) ? 1 : -1;
                int ledIndex = (f / 10) % CHANNELS_PHONE3A;
                int activeIndex = direction == 1 ? ledIndex : (CHANNELS_PHONE3A - 1 - ledIndex);
                frameBrightness[activeIndex] = MAX_BRIGHTNESS;
            } else if (loudness >= dynamicAmplitudeThreshold) {
                fillWindowedSignal(channelData, startIdx, hannWindow, real, imag);
                fft.transform(real, imag);
                computeAmplitudeSpectrum(real, imag, ampSpectrum);

                float lowEnergy = calculateBandEnergy(ampSpectrum, 60, 250, sampleRate, BUFFER_SIZE);
                float midEnergy = calculateBandEnergy(ampSpectrum, 250, 2000, sampleRate, BUFFER_SIZE);
                float highEnergy = calculateBandEnergy(ampSpectrum, 2000, 20000, sampleRate, BUFFER_SIZE);

                int lowBright = clamp((int) ((lowEnergy / Math.max(loudness, 0.0001f)) * MAX_BRIGHTNESS), 0, MAX_BRIGHTNESS);
                int midBright = clamp((int) ((midEnergy / Math.max(loudness, 0.0001f)) * MAX_BRIGHTNESS), 0, MAX_BRIGHTNESS);
                int highBright = clamp((int) ((highEnergy / Math.max(loudness, 0.0001f)) * MAX_BRIGHTNESS), 0, MAX_BRIGHTNESS);

                for (int led : PHONE3A_ZONES[zoneMapping[0]]) frameBrightness[led] = lowBright;
                for (int led : PHONE3A_ZONES[zoneMapping[1]]) frameBrightness[led] = midBright;
                for (int led : PHONE3A_ZONES[zoneMapping[2]]) frameBrightness[led] = highBright;
            }

            for (int led = 0; led < CHANNELS_PHONE3A; led++) {
                if (antiFlicker) {
                    currentBrightness[led] = currentBrightness[led] * decayFactor + frameBrightness[led] * (1.0f - decayFactor);
                    frameBrightness[led] = clamp((int) currentBrightness[led], 0, MAX_BRIGHTNESS);
                }
                csv.append(frameBrightness[led]);
                if (led < CHANNELS_PHONE3A - 1) {
                    csv.append(", ");
                } else {
                    csv.append(",\n");
                }
            }
        }

        return csv.toString();
    }

    /**
     * Mode 2: Frequency Mode.
     * 7-band acoustic spectrum analysis (Sub-bass to Very-high), sustain counters,
     * and frequency-assigned physical LED lighting for Phone (3a).
     */
    private static String generateFrequency(float[] channelData, int sampleRate) {
        int hopSize = Math.max(1, (int) Math.floor((16.666f / 1000f) * sampleRate));
        int totalFrames = Math.max(1, (channelData.length - BUFFER_SIZE) / hopSize + 1);

        FastFFT fft = new FastFFT(BUFFER_SIZE);
        float[] hannWindow = createHannWindow(BUFFER_SIZE);

        float totalAmplitude = 0f;
        float[] avgBandEnergies = new float[7];
        float[] real = new float[BUFFER_SIZE];
        float[] imag = new float[BUFFER_SIZE];
        float[] ampSpectrum = new float[BUFFER_SIZE / 2 + 1];

        // Pass 1: compute band averages
        for (int f = 0; f < totalFrames; f++) {
            int startIdx = f * hopSize;
            float l = calculateRms(channelData, startIdx, BUFFER_SIZE);
            totalAmplitude += l;

            fillWindowedSignal(channelData, startIdx, hannWindow, real, imag);
            fft.transform(real, imag);
            computeAmplitudeSpectrum(real, imag, ampSpectrum);

            for (int b = 0; b < 7; b++) {
                avgBandEnergies[b] += calculateBandEnergy(ampSpectrum, FREQ_RANGES[b][0], FREQ_RANGES[b][1], sampleRate, BUFFER_SIZE);
            }
        }

        float amplitudeThreshold = (totalAmplitude / totalFrames) * 0.6f;
        float[] bandThresholds = new float[7];
        for (int b = 0; b < 7; b++) {
            bandThresholds[b] = (avgBandEnergies[b] / totalFrames) * 0.7f;
        }

        // Pass 2: generation with sustain and decay
        float[] currentBrightness = new float[CHANNELS_PHONE3A];
        int[] sustainCounters = new int[CHANNELS_PHONE3A];
        int sustainDuration = 5;
        float decayFactor = 0.85f;

        StringBuilder csv = new StringBuilder(totalFrames * 150);

        for (int f = 0; f < totalFrames; f++) {
            int startIdx = f * hopSize;
            float loudness = calculateRms(channelData, startIdx, BUFFER_SIZE);
            int[] frameBrightness = new int[CHANNELS_PHONE3A];

            if (loudness >= amplitudeThreshold) {
                fillWindowedSignal(channelData, startIdx, hannWindow, real, imag);
                fft.transform(real, imag);
                computeAmplitudeSpectrum(real, imag, ampSpectrum);

                for (int b = 0; b < 7; b++) {
                    float bandEnergy = calculateBandEnergy(ampSpectrum, FREQ_RANGES[b][0], FREQ_RANGES[b][1], sampleRate, BUFFER_SIZE);
                    if (bandEnergy > bandThresholds[b]) {
                        int bright = clamp((int) ((bandEnergy / Math.max(loudness, 0.0001f)) * MAX_BRIGHTNESS), 0, MAX_BRIGHTNESS);
                        for (int led : FREQ_BAND_LEDS[b]) {
                            sustainCounters[led] = sustainDuration;
                            currentBrightness[led] = bright;
                        }
                    }
                }
            }

            for (int led = 0; led < CHANNELS_PHONE3A; led++) {
                if (sustainCounters[led] > 0) {
                    sustainCounters[led]--;
                } else {
                    currentBrightness[led] *= decayFactor;
                }
                frameBrightness[led] = clamp((int) currentBrightness[led], 0, MAX_BRIGHTNESS);
                csv.append(frameBrightness[led]);
                if (led < CHANNELS_PHONE3A - 1) {
                    csv.append(", ");
                } else {
                    csv.append(",\n");
                }
            }
        }

        return csv.toString();
    }

    /**
     * Mode 3: Dynamic Pattern Mode.
     * Reacts to beats, bass spikes, and musical transients to choreograph
     * dynamic patterns (Wave, Pulse, Bounce, Spiral, Chase, Ripple) across Phone (3a) LEDs.
     */
    private static String generatePattern(float[] channelData, int sampleRate) {
        int hopSize = Math.max(1, (int) Math.floor((16.666f / 1000f) * sampleRate));
        int totalFrames = Math.max(1, (channelData.length - BUFFER_SIZE) / hopSize + 1);

        FastFFT fft = new FastFFT(BUFFER_SIZE);
        float[] hannWindow = createHannWindow(BUFFER_SIZE);

        float[] currentBrightness = new float[CHANNELS_PHONE3A];
        float decayFactor = 0.88f;

        int patternType = 0; // 0=Wave, 1=Pulse, 2=Bounce, 3=Spiral, 4=Chase
        float patternPosition = 0;
        int patternDirection = 1;

        StringBuilder csv = new StringBuilder(totalFrames * 150);
        float[] real = new float[BUFFER_SIZE];
        float[] imag = new float[BUFFER_SIZE];
        float[] ampSpectrum = new float[BUFFER_SIZE / 2 + 1];

        float avgEnergy = 0.01f;
        float avgBass = 0.01f;

        for (int f = 0; f < totalFrames; f++) {
            int startIdx = f * hopSize;
            float energy = calculateRms(channelData, startIdx, BUFFER_SIZE);

            fillWindowedSignal(channelData, startIdx, hannWindow, real, imag);
            fft.transform(real, imag);
            computeAmplitudeSpectrum(real, imag, ampSpectrum);

            float bassEnergy = calculateBandEnergy(ampSpectrum, 20, 250, sampleRate, BUFFER_SIZE);
            avgEnergy = avgEnergy * 0.95f + energy * 0.05f;
            avgBass = avgBass * 0.95f + bassEnergy * 0.05f;

            float bassRatio = bassEnergy / Math.max(avgBass, 0.0001f);
            float energyRatio = energy / Math.max(avgEnergy, 0.0001f);

            // Pattern switcher on musical changes
            if (f % 45 == 0) {
                if (bassRatio > 1.4f) {
                    patternType = (patternType == 1) ? 2 : 1; // Pulse or Bounce
                } else if (energyRatio > 1.3f) {
                    patternType = (patternType == 0) ? 3 : 0; // Wave or Spiral
                } else {
                    patternType = (patternType + 1) % 5;
                }
            }

            // Progression speed adapts to audio energy
            float stepSpeed = Math.min(2.5f, Math.max(0.6f, energyRatio * 1.2f));
            patternPosition += patternDirection * stepSpeed;
            if (patternPosition >= CHANNELS_PHONE3A) {
                patternPosition = 0;
            } else if (patternPosition < 0) {
                patternPosition = CHANNELS_PHONE3A - 1;
            }

            int[] frameBrightness = new int[CHANNELS_PHONE3A];
            int peakBright = clamp((int) (Math.min(1.0f, energy * 3.5f) * MAX_BRIGHTNESS), 0, MAX_BRIGHTNESS);

            switch (patternType) {
                case 0: // Wave (Undulating ripple along full perimeter)
                    for (int led = 0; led < CHANNELS_PHONE3A; led++) {
                        float dist = Math.abs(led - patternPosition);
                        if (dist > CHANNELS_PHONE3A / 2.0f) dist = CHANNELS_PHONE3A - dist;
                        float falloff = (float) Math.exp(-(dist * dist) / 10.0);
                        frameBrightness[led] = clamp((int) (peakBright * falloff), 0, MAX_BRIGHTNESS);
                    }
                    break;
                case 1: // Pulse (Center circle bursts on bass hits, radiating to outer arcs)
                    for (int led : PHONE3A_ZONES[1]) {
                        frameBrightness[led] = peakBright;
                    }
                    float outerFactor = Math.max(0f, 1.0f - (f % 10) / 10.0f);
                    for (int led : PHONE3A_ZONES[0]) {
                        frameBrightness[led] = clamp((int) (peakBright * outerFactor * 0.8f), 0, MAX_BRIGHTNESS);
                    }
                    for (int led : PHONE3A_ZONES[2]) {
                        frameBrightness[led] = clamp((int) (peakBright * outerFactor), 0, MAX_BRIGHTNESS);
                    }
                    break;
                case 2: // Bounce (Head travels and bounces at boundaries)
                    for (int led = 0; led < CHANNELS_PHONE3A; led++) {
                        float dist = Math.abs(led - patternPosition);
                        float falloff = (float) Math.exp(-(dist * dist) / 6.0);
                        frameBrightness[led] = clamp((int) (peakBright * falloff), 0, MAX_BRIGHTNESS);
                    }
                    if (patternPosition >= CHANNELS_PHONE3A - 2) patternDirection = -1;
                    if (patternPosition <= 1) patternDirection = 1;
                    break;
                case 3: // Spiral (Circular arc rotates like a spinning beacon)
                    int circleOffset = (int) (patternPosition) % 11;
                    for (int idx = 0; idx < 11; idx++) {
                        int led = PHONE3A_ZONES[1][idx];
                        float dist = Math.abs(idx - circleOffset);
                        float falloff = (float) Math.exp(-(dist * dist) / 3.0);
                        frameBrightness[led] = clamp((int) (peakBright * falloff), 0, MAX_BRIGHTNESS);
                    }
                    if (bassRatio > 1.2f) {
                        for (int led : PHONE3A_ZONES[2]) frameBrightness[led] = peakBright;
                    }
                    break;
                case 4: // Chase (Fast transient runner)
                default:
                    for (int led = 0; led < CHANNELS_PHONE3A; led++) {
                        float dist = Math.abs(led - patternPosition);
                        float falloff = (float) Math.exp(-(dist * dist) / 4.0);
                        frameBrightness[led] = clamp((int) (peakBright * falloff), 0, MAX_BRIGHTNESS);
                    }
                    break;
            }

            for (int led = 0; led < CHANNELS_PHONE3A; led++) {
                currentBrightness[led] = currentBrightness[led] * decayFactor + frameBrightness[led] * (1.0f - decayFactor);
                int outVal = clamp((int) currentBrightness[led], 0, MAX_BRIGHTNESS);
                csv.append(outVal);
                if (led < CHANNELS_PHONE3A - 1) {
                    csv.append(", ");
                } else {
                    csv.append(",\n");
                }
            }
        }

        return csv.toString();
    }

    /**
     * Decode any audio format (MP3, WAV, AAC, FLAC, OGG, M4A) via MediaExtractor + MediaCodec
     * to a normalized float array [-1.0, 1.0] representing mono PCM audio samples.
     */
    public static float[] decodeToPcm(Context context, Uri uri, int[] outSampleRate) throws IOException {
        android.content.res.AssetFileDescriptor afd = null;
        try {
            afd = context.getContentResolver().openAssetFileDescriptor(uri, "r");
            if (afd != null) {
                MediaExtractor extractor = new MediaExtractor();
                if (afd.getDeclaredLength() < 0) {
                    extractor.setDataSource(afd.getFileDescriptor());
                } else {
                    extractor.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getDeclaredLength());
                }
                return decodeWithExtractor(extractor, outSampleRate);
            }
        } catch (Exception e) {
            Log.d(TAG, "Direct AssetFileDescriptor extraction failed for " + uri + ": " + e.getMessage());
        } finally {
            if (afd != null) {
                try {
                    afd.close();
                } catch (Exception ignored) {}
            }
        }

        // Robust fallback: copy stream to temporary cache file
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is != null) {
                File tempFile = File.createTempFile("glyph_audio_", ".tmp", context.getCacheDir());
                try {
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile)) {
                        byte[] buf = new byte[8192];
                        int r;
                        while ((r = is.read(buf)) != -1) {
                            fos.write(buf, 0, r);
                        }
                    }
                    return decodeToPcm(tempFile, outSampleRate);
                } finally {
                    tempFile.delete();
                }
            }
        } catch (Exception ex) {
            Log.w(TAG, "Fallback to temp file failed: " + ex.getMessage());
        }

        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(context, uri, null);
        return decodeWithExtractor(extractor, outSampleRate);
    }


    public static float[] decodeToPcm(File file, int[] outSampleRate) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try (FileInputStream fis = new FileInputStream(file)) {
            extractor.setDataSource(fis.getFD());
            return decodeWithExtractor(extractor, outSampleRate);
        }
    }

    private static float[] decodeWithExtractor(MediaExtractor extractor, int[] outSampleRate) throws IOException {
        int audioTrackIndex = -1;
        MediaFormat audioFormat = null;

        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                audioTrackIndex = i;
                audioFormat = format;
                break;
            }
        }

        if (audioTrackIndex < 0 || audioFormat == null) {
            extractor.release();
            throw new IOException("No audio track found in media source");
        }

        extractor.selectTrack(audioTrackIndex);
        int sampleRate = audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
        int channelCount = audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;

        if (outSampleRate != null && outSampleRate.length > 0) {
            outSampleRate[0] = sampleRate;
        }

        String mime = audioFormat.getString(MediaFormat.KEY_MIME);
        MediaCodec codec = MediaCodec.createDecoderByType(mime);
        codec.configure(audioFormat, null, null, 0);
        codec.start();

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean isEOS = false;
        ByteArrayOutputStream pcmOut = new ByteArrayOutputStream();

        final long TIMEOUT_US = 10000;
        try {
            while (!isEOS) {
                int inIndex = codec.dequeueInputBuffer(TIMEOUT_US);
                if (inIndex >= 0) {
                    ByteBuffer buffer = codec.getInputBuffer(inIndex);
                    if (buffer != null) {
                        int sampleSize = extractor.readSampleData(buffer, 0);
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            isEOS = true;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                while (outIndex >= 0) {
                    ByteBuffer outBuffer = codec.getOutputBuffer(outIndex);
                    if (outBuffer != null && info.size > 0) {
                        outBuffer.position(info.offset);
                        outBuffer.limit(info.offset + info.size);
                        byte[] chunk = new byte[info.size];
                        outBuffer.get(chunk);
                        pcmOut.write(chunk, 0, chunk.length);
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    outIndex = codec.dequeueOutputBuffer(info, 0);
                }
            }
        } finally {
            try {
                codec.stop();
            } catch (Exception ignored) {}
            codec.release();
            extractor.release();
        }

        byte[] pcmBytes = pcmOut.toByteArray();
        if (pcmBytes.length == 0) {
            return new float[0];
        }

        int totalSamples = pcmBytes.length / (2 * Math.max(1, channelCount));
        float[] channelData = new float[totalSamples];
        ByteBuffer byteBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN);
        ShortBuffer shortBuffer = byteBuffer.asShortBuffer();

        for (int i = 0; i < totalSamples; i++) {
            float sum = 0f;
            for (int ch = 0; ch < channelCount; ch++) {
                if (shortBuffer.hasRemaining()) {
                    sum += shortBuffer.get();
                }
            }
            channelData[i] = (sum / channelCount) / 32768.0f;
        }

        return channelData;
    }

    /**
     * Compress CSV animation data into Nothing OS Base64(Deflate(csv)) AUTHOR metadata format.
     */
    public static String compressCsvToAuthorTag(String csv) throws IOException {
        if (csv == null || csv.isEmpty()) return "";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try (DeflaterOutputStream dos = new DeflaterOutputStream(baos, deflater)) {
            dos.write(csv.getBytes(StandardCharsets.UTF_8));
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    // --- Helper Math Methods ---

    private static float[] createHannWindow(int size) {
        float[] window = new float[size];
        for (int i = 0; i < size; i++) {
            window[i] = (float) (0.5 * (1.0 - Math.cos((2.0 * Math.PI * i) / (size - 1))));
        }
        return window;
    }

    private static void fillWindowedSignal(float[] channelData, int startIdx, float[] window, float[] real, float[] imag) {
        for (int i = 0; i < BUFFER_SIZE; i++) {
            int dataIdx = startIdx + i;
            real[i] = (dataIdx < channelData.length ? channelData[dataIdx] : 0f) * window[i];
            imag[i] = 0f;
        }
    }

    private static void computeAmplitudeSpectrum(float[] real, float[] imag, float[] ampSpectrum) {
        for (int i = 0; i <= BUFFER_SIZE / 2; i++) {
            ampSpectrum[i] = (float) Math.sqrt(real[i] * real[i] + imag[i] * imag[i]) / BUFFER_SIZE;
        }
    }

    public static float calculateBandEnergy(float[] amplitudeSpectrum, float minFreq, float maxFreq, int sampleRate, int bufferSize) {
        float nyquist = sampleRate / 2.0f;
        int startBin = Math.max(0, (int) Math.floor((minFreq / nyquist) * (bufferSize / 2.0f)));
        int endBin = Math.min(bufferSize / 2, (int) Math.ceil((maxFreq / nyquist) * (bufferSize / 2.0f)));
        float energy = 0f;
        for (int i = startBin; i <= endBin; i++) {
            energy += amplitudeSpectrum[i] * amplitudeSpectrum[i];
        }
        return (float) Math.sqrt(energy);
    }

    public static float calculateRms(float[] channelData, int startIdx, int bufferSize) {
        float sum = 0f;
        int end = Math.min(channelData.length, startIdx + bufferSize);
        int count = end - startIdx;
        if (count <= 0) return 0f;
        for (int i = startIdx; i < end; i++) {
            float val = channelData[i];
            sum += val * val;
        }
        return (float) Math.sqrt(sum / count);
    }

    public static int clamp(int val, int min, int max) {
        return Math.min(Math.max(val, min), max);
    }

    /**
     * In-place Radix-2 Cooley-Tukey FFT with precomputed trigonometric tables.
     */
    public static class FastFFT {
        private final int n;
        private final float[] cosTable;
        private final float[] sinTable;
        private final int[] bitReverse;

        public FastFFT(int n) {
            this.n = n;
            cosTable = new float[n / 2];
            sinTable = new float[n / 2];
            for (int i = 0; i < n / 2; i++) {
                double angle = -2.0 * Math.PI * i / n;
                cosTable[i] = (float) Math.cos(angle);
                sinTable[i] = (float) Math.sin(angle);
            }
            bitReverse = new int[n];
            int shift = 1 + Integer.numberOfLeadingZeros(n);
            for (int i = 0; i < n; i++) {
                bitReverse[i] = Integer.reverse(i) >>> shift;
            }
        }

        public void transform(float[] real, float[] imag) {
            for (int i = 0; i < n; i++) {
                int rev = bitReverse[i];
                if (i < rev) {
                    float tr = real[i]; real[i] = real[rev]; real[rev] = tr;
                    float ti = imag[i]; imag[i] = imag[rev]; imag[rev] = ti;
                }
            }
            for (int len = 2; len <= n; len <<= 1) {
                int half = len >> 1;
                int step = n / len;
                for (int i = 0; i < n; i += len) {
                    for (int j = 0; j < half; j++) {
                        int k = j * step;
                        float c = cosTable[k];
                        float s = sinTable[k];
                        int u = i + j;
                        int v = i + j + half;
                        float tr = real[v] * c - imag[v] * s;
                        float ti = real[v] * s + imag[v] * c;
                        real[v] = real[u] - tr;
                        imag[v] = imag[u] - ti;
                        real[u] += tr;
                        imag[u] += ti;
                    }
                }
            }
        }
    }
}
