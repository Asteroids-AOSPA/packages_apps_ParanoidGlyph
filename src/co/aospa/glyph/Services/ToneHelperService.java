package co.aospa.glyph.Services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import androidx.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import co.aospa.glyph.Constants.Constants;
import co.aospa.glyph.Manager.AnimationManager;
import co.aospa.glyph.Manager.SettingsManager;
import co.aospa.glyph.Utils.AnimationUtils;
import co.aospa.glyph.Utils.AudioGlyphGenerator;
import co.aospa.glyph.Utils.OGGParser;

import co.aospa.glyph.R;

public class ToneHelperService extends Service {

    private ContentObserver settingsObserver;
    private ContentResolver resolver;

    private Uri notificationUri;
    private Uri ringtoneUri;
    private Uri ringtone2Uri;

    private boolean saveRing;
    private boolean saveNotif;

    private static final String INTENT_EXTRA_KEY = "changed_key";
    private static final String INTENT_EXTRA_VALUE = "changed_value";

    private static final String CHANNEL_ID = "tone_changes";

    // Keep a map of URI -> key so we can reverse-look it up in onChange
    private final Map<Uri, String> observedUris = new LinkedHashMap<>();

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private final String TAG = this.getClass().getSimpleName();

    @Override
    public void onCreate() {
        if (Constants.CONTEXT == null) {
            Constants.CONTEXT = getApplicationContext();
        }
        resolver = getContentResolver();
        notificationUri = Settings.System.getUriFor(Settings.System.NOTIFICATION_SOUND);
        ringtoneUri = Settings.System.getUriFor(Settings.System.RINGTONE);
        ringtone2Uri = Settings.System.getUriFor(Settings.System.RINGTONE + "2");
        registerObserver();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        saveRing = SettingsManager.isGlyphRingtoneSyncEnabled();
        saveNotif = SettingsManager.isGlyphNotifsSyncEnabled();

        if (saveRing) {
            String key = Settings.System.RINGTONE;
            String val = Settings.System.getString(resolver, key);
            if (val != null) {
                mExecutor.submit(() -> handleSettingChange(key, val, false));
            }
        } else {
            AnimationUtils.Holder.Call.clear();
        }

        if (saveNotif) {
            String key = Settings.System.NOTIFICATION_SOUND;
            String val = Settings.System.getString(resolver, key);
            if (val != null) {
                mExecutor.submit(() -> handleSettingChange(key, val, false));
            }
        } else {
            AnimationUtils.Holder.Notification.clear();
        }

        return START_STICKY;
    }

    private void registerObserver() {

        observedUris.put(notificationUri, Settings.System.NOTIFICATION_SOUND);
        observedUris.put(ringtoneUri, Settings.System.RINGTONE);
        observedUris.put(ringtone2Uri, Settings.System.RINGTONE + "2");

        settingsObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                String key = observedUris.get(uri);
                if (key == null) return;

                String value = Settings.System.getString(resolver, key);
                if (value == null) return;

                mExecutor.submit(() -> handleSettingChange(key, value, true));
            }
        };

        for (Uri uri : observedUris.keySet()) {
            resolver.registerContentObserver(uri, false, settingsObserver);
        }
    }

    private void handleSettingChange(String key, String value, boolean shouldNotify) {
        saveRing = SettingsManager.isGlyphRingtoneSyncEnabled();
        saveNotif = SettingsManager.isGlyphNotifsSyncEnabled();
        boolean isOgg = false;
        try {
            Uri uri = Uri.parse(value);
            String mimeType = resolver.getType(uri);
            if ("audio/ogg".equals(mimeType)
                    || "audio/x-ogg".equals(mimeType)
                    || "application/ogg".equals(mimeType)
                    || "audio/opus".equals(mimeType)) {
                isOgg = true;
            } else if (value.toLowerCase().endsWith(".ogg") || value.toLowerCase().endsWith(".opus")) {
                isOgg = true;
            } else {
                try (InputStream is = resolver.openInputStream(uri)) {
                    byte[] magic = new byte[4];
                    if (is != null && is.read(magic) == 4) {
                        isOgg = (magic[0] == 'O' && magic[1] == 'g' && magic[2] == 'g' && magic[3] == 'S');
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not resolve MIME type or header for: " + value, e);
        }

        if (!isOgg) {
            Log.d(TAG, key + " changed to non-OGG audio (" + value + "), attempting fallback auto-generation");
            autoGenerateFallback(key, Uri.parse(value), value, shouldNotify);
            return;
        }

        Log.d(TAG, key + " changed to OGG: " + value);

        tryAnimationData(key, value, shouldNotify);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_title),
                NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription(getString(R.string.notification_channel_description));
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mExecutor.shutdown();
        if (settingsObserver != null) {
            getContentResolver().unregisterContentObserver(settingsObserver);
        }
        AnimationUtils.Holder.Call.clear();
        AnimationUtils.Holder.Notification.clear();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void notifyUser(String key, String value) {

        String title = "";
        String message = getString(R.string.sound_changed_notification_message);

        switch (key) {
            case Settings.System.RINGTONE -> {
                title = getString(R.string.ringtone_changed_notification_title);
            }
            case Settings.System.RINGTONE + "2" -> {
                title = getString(R.string.ringtone_changed_notification_title)
                        + " (SIM 2)";
            }
            case Settings.System.NOTIFICATION_SOUND -> {
                title = getString(R.string.notification_sound_changed_notification_title);
            }
        }

        Intent actionIntent = new Intent(getApplicationContext(), this.getClass());
        actionIntent.putExtra(INTENT_EXTRA_KEY, key);
        actionIntent.putExtra(INTENT_EXTRA_VALUE, value);


        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_glyphs_logo)
                .setContentTitle(title)
                .setContentText(message)
                .setAutoCancel(true)
                .build();

        getSystemService(NotificationManager.class).notify(key.hashCode(), notification);

    }

    private void clearHolder(String key) {
        switch (key) {
            case Settings.System.RINGTONE, Settings.System.RINGTONE + "2" -> {
                AnimationUtils.Holder.Call.clear();
            }
            case Settings.System.NOTIFICATION_SOUND -> {
                AnimationUtils.Holder.Notification.clear();
            }
        }
    }

    private void autoGenerateFallback(String key, Uri soundUri, String value, boolean shouldNotify) {
        if (!Constants.Device.isPhone3a()) {
            clearHolder(key);
            return;
        }

        boolean isRing = key.equals(Settings.System.RINGTONE) || key.equals(Settings.System.RINGTONE + "2");
        if (isRing && !saveRing) {
            clearHolder(key);
            return;
        }
        if (!isRing && !saveNotif) {
            clearHolder(key);
            return;
        }

        String lastUriKey = isRing ? "glyph_last_gen_ring_uri" : "glyph_last_gen_notif_uri";
        String lastUri = PreferenceManager.getDefaultSharedPreferences(this).getString(lastUriKey, null);
        if (soundUri.toString().equals(lastUri)) {
            if (isRing && AnimationUtils.Holder.Call.isAvailable()) {
                Log.d(TAG, "Phone (3a) auto-generated call animation already cached for " + value);
                return;
            } else if (!isRing && AnimationUtils.Holder.Notification.isAvailable()) {
                Log.d(TAG, "Phone (3a) auto-generated notif animation already cached for " + value);
                return;
            }
        }

        Log.d(TAG, "Attempting Phone (3a) auto-generation for " + key + ": " + value);
        try {
            int mode = isRing ? AudioGlyphGenerator.MODE_SMOOTH : AudioGlyphGenerator.MODE_BEATS;
            String generatedCsv = AudioGlyphGenerator.generateForPhone3a(this, soundUri, mode);
            if (generatedCsv != null && !generatedCsv.isEmpty()) {
                if (isRing) {
                    AnimationUtils.Holder.Call.setCsv(generatedCsv, true);
                    AnimationManager.clearCallAnimationCache();
                } else {
                    AnimationUtils.Holder.Notification.setCsv(generatedCsv, true);
                }
                PreferenceManager.getDefaultSharedPreferences(this).edit().putString(lastUriKey, soundUri.toString()).apply();
                if (shouldNotify) notifyUser(key, value);
                Log.d(TAG, "Phone (3a) animation auto-generated & saved successfully for " + key);
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "Auto-generation failed for " + key + ": " + e.getMessage());
        }

        clearHolder(key);
    }

    private void tryAnimationData(String key, String value, boolean shouldNotify) {
        OGGParser parser = null;
        Uri soundUri = Uri.parse(value);
        try (InputStream inputStream = resolver.openInputStream(soundUri)) {
            if (inputStream == null) {
                clearHolder(key);
                return;
            }
            parser = new OGGParser(inputStream);
        } catch (FileNotFoundException e) {
            Log.w(TAG, "Audio file not found: " + value);
            clearHolder(key);
            return;
        } catch (Exception e) {
            Log.d(TAG, "Non-OGG or non-standard file: " + e.getMessage() + "; falling back to auto-generator");
            autoGenerateFallback(key, soundUri, value, shouldNotify);
            return;
        }

        String animationData = null;
        try {
            animationData = parser.getAnimation();
        } catch (Exception e) {
            animationData = null;
        }

        if (animationData != null && !animationData.isEmpty() && AnimationUtils.isCompatible(animationData)) {
            Log.d(TAG, "Embedded compatible animation data found!");
            switch (key) {
                case Settings.System.RINGTONE, Settings.System.RINGTONE + "2" -> {
                    if (saveRing) {
                        if (AnimationUtils.Holder.Call.isAvailable()
                                && AnimationUtils.Holder.Call.getCsv() != null
                                && AnimationUtils.Holder.Call.getCsv().equals(animationData)) {
                            return;
                        }
                        AnimationUtils.Holder.Call.setCsv(animationData, true);
                        if (shouldNotify) notifyUser(key, value);
                    }
                }
                case Settings.System.NOTIFICATION_SOUND -> {
                    if (saveNotif) {
                        if (AnimationUtils.Holder.Notification.isAvailable()
                                && AnimationUtils.Holder.Notification.getCsv() != null
                                && AnimationUtils.Holder.Notification.getCsv().equals(animationData)) {
                            return;
                        }
                        AnimationUtils.Holder.Notification.setCsv(animationData, true);
                        if (shouldNotify) notifyUser(key, value);
                    }
                }
            }
        } else {
            autoGenerateFallback(key, soundUri, value, shouldNotify);
        }
    }

}
