package co.aospa.glyph.Settings.Utilities;

import static co.aospa.glyph.Utils.InterfaceUtils.showDialog;
import static co.aospa.glyph.Utils.InterfaceUtils.showToast;
import static co.aospa.glyph.Utils.ResourceUtils.getFileName;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.preference.Preference;

import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import co.aospa.glyph.Constants.Constants;
import co.aospa.glyph.Manager.AnimationManager;
import co.aospa.glyph.R;
import co.aospa.glyph.Settings.OggSettingsFragment;
import co.aospa.glyph.Settings.SubSettingsActivity;
import co.aospa.glyph.Utils.AnimationUtils;
import co.aospa.glyph.Utils.AudioGlyphGenerator;
import co.aospa.glyph.Utils.OGGParser;

public class UtilitiesFragment extends SettingsBasePreferenceFragment {

    private String csvContent;
    private final String TAG = getClass().getSimpleName();

    private Consumer<Uri> mFilePickerAction;

    private final String[] csvMime = {"text/csv", "text/comma-separated-values"};
    private final String[] oggMime = {"audio/ogg", "application/ogg"};
    private final String[] audioMime = {"audio/*", "application/ogg"};

    private OGGParser parser;

    public static final String mapKeyDevice = "Device";
    public static final String mapKeyAnimLength = "Length";
    public static final String mapKeyAudioLength = "audioLength";
    public static final String mapKeyFilename = "Filename";

    // Auto Generator state
    private String mGeneratedCsv;
    private Uri mSelectedAudioUri;
    private MediaPlayer mMediaPlayer;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String> mSaveCsvLauncher = registerForActivityResult(
        new ActivityResultContracts.CreateDocument("text/csv"), uri -> {
            if (uri != null && mGeneratedCsv != null) {
                try (OutputStream os = requireContext().getContentResolver().openOutputStream(uri)) {
                    os.write(mGeneratedCsv.getBytes(StandardCharsets.UTF_8));
                    showToast(getString(R.string.file_export_complete_message));
                } catch (Exception e) {
                    Log.e(TAG, "Failed to save CSV", e);
                    showToast("Failed to save CSV file!");
                }
            }
        }
    );

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.glyph_utilities);
        getActivity().setTitle(R.string.glyph_settings_utilities_title);

        Preference autoGenPref = findPreference(Constants.GLYPH_UTILITIES_AUTO_GENERATE);
        if (autoGenPref != null && !Constants.Device.isPhone3a()) {
            autoGenPref.setSummary("Only supported on Phone (3a)");
        }
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        String preferenceKey = preference.getKey();
        switch (preferenceKey) {
            case Constants.GLYPH_UTILITIES_VALIDATE_CSV -> {
                mFilePickerAction = uri -> {
                    try {
                        InputStream is = requireContext().getContentResolver().openInputStream(uri);
                        csvContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                        showCSVresult(AnimationUtils.validateAnimationWithList(csvContent), uri);
                    } catch (Exception e) {
                        showDialog(requireActivity(),
                                "Error reading CSV file!", "",
                                "OK", null,
                                null, null);
                        Log.w(TAG, e);
                    }
                };
                mFilePicker.launch(csvMime);
            }
            case Constants.GLYPH_UTILITIES_READ_OGG -> {
                mFilePickerAction = uri -> {
                    try {
                        InputStream is = requireContext().getContentResolver().openInputStream(uri);
                        parser = new OGGParser(is);
                        showUserOggMeta(uri);
                    } catch (Exception e) {
                        showDialog(requireActivity(),
                                "Error parsing OGG file", "",
                                "OK", null,
                                null, null);
                        Log.w(TAG, e);
                    }
                };
                mFilePicker.launch(oggMime);
            }
            case Constants.GLYPH_UTILITIES_AUTO_GENERATE -> {
                showSourceSelectionDialog();
            }
        }
        return true;
    }

    private final ActivityResultLauncher<String[]> mFilePicker = registerForActivityResult(
        new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null && mFilePickerAction != null) {
                mFilePickerAction.accept(uri);
            }
        }
    );

    private void showSourceSelectionDialog() {
        android.content.Context context = requireContext();
        Uri ringtoneUri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE);
        if (ringtoneUri == null) {
            String ringtoneSetting = Settings.System.getString(context.getContentResolver(), Settings.System.RINGTONE);
            if (ringtoneSetting != null) {
                ringtoneUri = Uri.parse(ringtoneSetting);
            }
        }

        Uri notifUri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION);
        if (notifUri == null) {
            String notifSetting = Settings.System.getString(context.getContentResolver(), Settings.System.NOTIFICATION_SOUND);
            if (notifSetting != null) {
                notifUri = Uri.parse(notifSetting);
            }
        }

        String ringtoneTitle = getAudioTitle(ringtoneUri, getString(R.string.glyph_generator_source_default_ringtone));
        String notifTitle = getAudioTitle(notifUri, getString(R.string.glyph_generator_source_default_notif));

        String[] sources = new String[] {
            String.format(getString(R.string.glyph_generator_source_ringtone), ringtoneTitle),
            String.format(getString(R.string.glyph_generator_source_notif), notifTitle),
            getString(R.string.glyph_generator_source_file)
        };

        final Uri finalRingtoneUri = ringtoneUri;
        final String finalRingtoneTitle = ringtoneTitle;
        final Uri finalNotifUri = notifUri;
        final String finalNotifTitle = notifTitle;

        new AlertDialog.Builder(requireActivity())
                .setTitle(R.string.glyph_generator_source_title)
                .setItems(sources, (dialog, which) -> {
                    switch (which) {
                        case 0 -> { // Current Ringtone
                            if (finalRingtoneUri != null) {
                                mSelectedAudioUri = finalRingtoneUri;
                                showModeSelectionDialog(finalRingtoneUri, finalRingtoneTitle);
                            } else {
                                showToast(getString(R.string.glyph_generator_no_ringtone));
                            }
                        }
                        case 1 -> { // Current Notification
                            if (finalNotifUri != null) {
                                mSelectedAudioUri = finalNotifUri;
                                showModeSelectionDialog(finalNotifUri, finalNotifTitle);
                            } else {
                                showToast(getString(R.string.glyph_generator_no_notif));
                            }
                        }
                        case 2 -> { // Choose Audio File
                            mFilePickerAction = uri -> {
                                mSelectedAudioUri = uri;
                                showModeSelectionDialog(uri, null);
                            };
                            mFilePicker.launch(audioMime);
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String getAudioTitle(Uri uri, String fallback) {
        if (uri == null) return fallback;
        try {
            Ringtone r = RingtoneManager.getRingtone(requireContext(), uri);
            if (r != null) {
                String title = r.getTitle(requireContext());
                if (title != null && !title.isEmpty()) {
                    return title;
                }
            }
        } catch (Exception ignored) {}
        try {
            String titleParam = uri.getQueryParameter("title");
            if (titleParam != null && !titleParam.isEmpty()) {
                return titleParam;
            }
        } catch (Exception ignored) {}
        try {
            String fileName = getFileName(requireContext(), uri);
            if (fileName != null && !fileName.isEmpty() && !"audio".equals(fileName)) {
                return fileName;
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    private void showModeSelectionDialog(Uri audioUri) {
        showModeSelectionDialog(audioUri, null);
    }

    private void showModeSelectionDialog(Uri audioUri, String customDisplayName) {
        String fileName = (customDisplayName != null && !customDisplayName.isEmpty())
                ? customDisplayName
                : getFileName(requireContext(), audioUri);
        String[] modes = new String[] {
            getString(R.string.glyph_generator_mode_beats),
            getString(R.string.glyph_generator_mode_smooth),
            getString(R.string.glyph_generator_mode_frequency),
            getString(R.string.glyph_generator_mode_pattern)
        };

        final int[] selectedMode = new int[]{AudioGlyphGenerator.MODE_SMOOTH};

        new AlertDialog.Builder(requireActivity())
                .setTitle(getString(R.string.glyph_generator_dialog_title) + " - " + fileName)
                .setSingleChoiceItems(modes, 1, (dialog, which) -> {
                    selectedMode[0] = which;
                })
                .setPositiveButton(R.string.glyph_generator_action_generate, (dialog, which) -> {
                    runGenerator(audioUri, selectedMode[0], fileName);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void runGenerator(Uri audioUri, int mode, String fileName) {
        ProgressDialog progressDialog = new ProgressDialog(requireActivity());
        progressDialog.setMessage(getString(R.string.glyph_generator_generating));
        progressDialog.setCancelable(false);
        progressDialog.show();

        mExecutor.submit(() -> {
            try {
                String csv = AudioGlyphGenerator.generateForPhone3a(requireContext(), audioUri, mode);
                mMainHandler.post(() -> {
                    progressDialog.dismiss();
                    if (csv == null || csv.isEmpty()) {
                        showToast("Failed to generate Glyph animation!");
                        return;
                    }
                    mGeneratedCsv = csv;
                    showGeneratorResultDialog(audioUri, fileName, csv);
                });
            } catch (Exception e) {
                Log.e(TAG, "Audio generation error", e);
                mMainHandler.post(() -> {
                    progressDialog.dismiss();
                    showToast("Error generating animation: " + e.getMessage());
                });
            }
        });
    }

    private void showGeneratorResultDialog(Uri audioUri, String fileName, String csv) {
        int[][] frames = AnimationUtils.parseCsvToFrames(csv);
        int frameCount = frames != null ? frames.length : 0;
        int durationSec = (int) Math.round(frameCount / 60.0);

        String title = getString(R.string.glyph_generator_dialog_title);
        String message = String.format(getString(R.string.glyph_generator_success), frameCount, durationSec);

        String[] actions = new String[] {
            getString(R.string.glyph_generator_action_preview),
            getString(R.string.glyph_generator_action_apply_call),
            getString(R.string.glyph_generator_action_apply_notif),
            getString(R.string.glyph_generator_action_export_csv)
        };

        AlertDialog dialog = new AlertDialog.Builder(requireActivity())
                .setTitle(title)
                .setMessage(message + "\n\nFile: " + fileName)
                .setItems(actions, (d, which) -> {
                    switch (which) {
                        case 0 -> { // Live Preview
                            startLivePreview(audioUri, frames);
                        }
                        case 1 -> { // Apply to Call
                            AnimationUtils.Holder.Call.setCsv(csv, true);
                            saveCsvToDirectory(csv, fileName, Constants.GLYPH_USER_CALL_CSV_PATH);
                            AnimationManager.clearCallAnimationCache();
                            showToast(getString(R.string.glyph_generator_applied_call));
                        }
                        case 2 -> { // Apply to Notification
                            AnimationUtils.Holder.Notification.setCsv(csv, true);
                            saveCsvToDirectory(csv, fileName, Constants.GLYPH_USER_NOTIF_CSV_PATH);
                            showToast(getString(R.string.glyph_generator_applied_notif));
                        }
                        case 3 -> { // Export CSV
                            int dot = fileName.lastIndexOf('.');
                            String baseName = dot != -1 ? fileName.substring(0, dot) : fileName;
                            mSaveCsvLauncher.launch(baseName + "_phone3a.csv");
                        }
                    }
                })
                .setNegativeButton(android.R.string.ok, (d, w) -> stopLivePreview())
                .setOnDismissListener(d -> stopLivePreview())
                .create();

        dialog.show();
    }

    private void startLivePreview(Uri audioUri, int[][] frames) {
        stopLivePreview();
        try {
            mMediaPlayer = new MediaPlayer();
            mMediaPlayer.setDataSource(requireContext(), audioUri);
            mMediaPlayer.prepare();
            mMediaPlayer.setOnCompletionListener(mp -> stopLivePreview());
            mMediaPlayer.start();

            if (frames != null && frames.length > 0) {
                AnimationManager.playCallSynced(frames, false);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start live preview", e);
            showToast("Failed to start live preview");
        }
    }

    private void stopLivePreview() {
        if (mMediaPlayer != null) {
            try {
                if (mMediaPlayer.isPlaying()) {
                    mMediaPlayer.stop();
                }
                mMediaPlayer.release();
            } catch (Exception ignored) {}
            mMediaPlayer = null;
        }
        AnimationManager.stopCall();
    }

    private void saveCsvToDirectory(String csv, String fileName, String subPath) {
        try {
            int dot = fileName.lastIndexOf('.');
            String baseName = dot != -1 ? fileName.substring(0, dot) : fileName;
            File dir = new File(Environment.getExternalStorageDirectory(), subPath);
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, baseName + ".csv");
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(csv);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not save CSV to " + subPath, e);
        }
    }

    private void showCSVresult(List<String> results, Uri contentUri) {
        View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_scrollable, null);
        TextView message = view.findViewById(R.id.dialog_message);
        String title;
        if (results.isEmpty()) {
            title = "Results";
            results.add("No errors found.");
        } else {
            int amount = results.size();
            String count = String.valueOf(amount);
            title = count + ((amount > 1) ? " errors found" : " error found");
        }
        results.addFirst("Reading file: " + getFileName(requireContext(), contentUri));
        message.setText(String.join("\n", results));
        showDialog(requireActivity(), title, view, android.R.string.ok,
                null, null, null);
    }

    private void showUserOggMeta(Uri contentUri) throws Exception {
        AnimationUtils.Holder.oggMeta.setMap(resolveUserOggMeta(contentUri));
        Intent intent = new Intent(requireContext(), SubSettingsActivity.class);
        intent.putExtra("fragment", OggSettingsFragment.class.getName());
        startActivity(intent);
    }

    private Map<String, String> resolveUserOggMeta(Uri contentUri) throws Exception {
        Map<String, String> newMeta = new HashMap<>();

        String csv = parser.getAnimation();
        String device = AnimationUtils.getDevice(csv);
        String fileName = getFileName(requireContext(), contentUri);

        newMeta.put(mapKeyFilename, fileName);
        newMeta.put(mapKeyDevice, device);
        newMeta.put(mapKeyAnimLength, String.valueOf(Math.round(AnimationUtils.calcAnimPlaytime(csv))));
        newMeta.put("csv", csv);

        return newMeta;
    }

    @Override
    public void onDestroy() {
        stopLivePreview();
        mExecutor.shutdown();
        super.onDestroy();
    }
}
