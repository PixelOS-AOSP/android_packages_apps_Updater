/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package net.pixelos.ota.controller;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.os.ServiceSpecificException;
import android.os.UpdateEngine;
import android.os.UpdateEngineCallback;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import androidx.preference.PreferenceManager;

import net.pixelos.ota.UpdaterApplication;
import net.pixelos.ota.data.Update;
import net.pixelos.ota.data.UpdateStatus;
import net.pixelos.ota.data.UserPreferencesRepository;
import net.pixelos.ota.download.SingleRangeHttpFetcher;
import net.pixelos.ota.misc.Constants;
import net.pixelos.ota.misc.Utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

class ABUpdateInstaller {

    private static final String TAG = "ABUpdateInstaller";

    private static final String PREF_INSTALLING_AB_ID = "installing_ab_id";
    private static final String PREF_INSTALLING_SUSPENDED_AB_ID = "installing_suspended_ab_id";
    private static final String PREF_NEEDS_REBOOT_BOOT_COUNT = "needs_reboot_boot_count";

    private static final long WAKELOCK_TIMEOUT = 60 * 60 * 1000;

    // Share of the progress for applying, verifying and finalizing the payload.
    // Streaming also downloads the payload while applying it.
    private static final int[] STAGE_WEIGHTS = {40, 5, 55};
    private static final int[] STAGE_WEIGHTS_STREAMING = {60, 5, 35};

    private static ABUpdateInstaller sInstance = null;

    private final UpdaterController mUpdaterController;
    private final UserPreferencesRepository mUserPreferencesRepository;
    private final Context mContext;
    private String mDownloadId;

    private final UpdateEngine mUpdateEngine;
    private boolean mBound;

    // update_engine doesn't keep the device awake, so the installation barely
    // progresses while the device is suspended.
    private final PowerManager.WakeLock mWakeLock;

    private boolean mStreaming;
    private boolean mFinalizing;
    private int mProgress;

    private final UpdateEngineCallback mUpdateEngineCallback = new UpdateEngineCallback() {

        @Override
        public void onStatusUpdate(int status, float percent) {
            Update update = mUpdaterController.getUpdate(mDownloadId);
            if (update == null) {
                // We read the id from a preference, the update could no longer exist
                installationDone(status == UpdateEngine.UpdateStatusConstants.UPDATED_NEED_REBOOT);
                return;
            }

            switch (status) {
                case UpdateEngine.UpdateStatusConstants.DOWNLOADING:
                case UpdateEngine.UpdateStatusConstants.VERIFYING:
                case UpdateEngine.UpdateStatusConstants.FINALIZING: {
                    if (update.getStatus() != UpdateStatus.INSTALLING) {
                        update = update.withStatus(UpdateStatus.INSTALLING);
                        mUpdaterController.setUpdate(mDownloadId, update);
                        mUpdaterController.notifyUpdateChange(mDownloadId);
                    }
                    int[] weights = getStageWeights();
                    int offset = 0;
                    int weight = weights[0];
                    if (status == UpdateEngine.UpdateStatusConstants.VERIFYING) {
                        offset = weights[0];
                        weight = weights[1];
                    } else if (status == UpdateEngine.UpdateStatusConstants.FINALIZING) {
                        offset = weights[0] + weights[1];
                        weight = weights[2];
                    }
                    mProgress = offset + Math.round(percent * weight);
                    mFinalizing = status == UpdateEngine.UpdateStatusConstants.FINALIZING;
                    update = update.toBuilder()
                            .setInstallProgress(mProgress)
                            .setFinalizing(mFinalizing)
                            .build();
                    mUpdaterController.setUpdate(mDownloadId, update);
                    mUpdaterController.notifyInstallProgress(mDownloadId);
                }
                break;

                case UpdateEngine.UpdateStatusConstants.UPDATED_NEED_REBOOT: {
                    installationDone(true);
                    update = update.toBuilder()
                            .setInstallProgress(0)
                            .setStatus(UpdateStatus.UPDATED_NEED_REBOOT)
                            .build();
                    mUpdaterController.setUpdate(mDownloadId, update);
                    mUpdaterController.notifyUpdateChange(mDownloadId);
                }
                break;

                case UpdateEngine.UpdateStatusConstants.IDLE: {
                    // The service was restarted because we thought we were installing an
                    // update, but we aren't, so clear everything.
                    installationDone(false);
                }
                break;
            }
        }

        @Override
        public void onPayloadApplicationComplete(int errorCode) {
            if (errorCode != UpdateEngine.ErrorCodeConstants.SUCCESS) {
                installationDone(false);
                mUpdaterController.markInstallationFailed(mDownloadId);
                mUpdaterController.notifyUpdateChange(mDownloadId);
            }
        }
    };

    private static int getBootCount(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 0);
    }

    // The id is only cleared once BOOT_COMPLETED is handled, so it's still set
    // for a while after the reboot. The boot count tells whether it happened.
    private static String getNeedsRebootId(Context context) {
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
        if (pref.getInt(PREF_NEEDS_REBOOT_BOOT_COUNT, -1) != getBootCount(context)) {
            return null;
        }
        return pref.getString(Constants.PREF_NEEDS_REBOOT_ID, null);
    }

    static synchronized boolean isInstallingUpdate(Context context) {
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
        return pref.getString(ABUpdateInstaller.PREF_INSTALLING_AB_ID, null) != null ||
                getNeedsRebootId(context) != null;
    }

    static synchronized boolean isInstallingUpdate(Context context, String downloadId) {
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
        return downloadId.equals(pref.getString(ABUpdateInstaller.PREF_INSTALLING_AB_ID, null)) ||
                TextUtils.equals(getNeedsRebootId(context), downloadId);
    }

    static synchronized boolean isInstallingUpdateSuspended(Context context) {
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
        return pref.getString(ABUpdateInstaller.PREF_INSTALLING_SUSPENDED_AB_ID, null) != null;
    }

    static synchronized boolean isWaitingForReboot(Context context, String downloadId) {
        return TextUtils.equals(getNeedsRebootId(context), downloadId);
    }

    private boolean shouldEnablePerformanceMode(boolean userPreferenceEnabled) {
        return ((UpdaterApplication) mContext).getBatteryMonitor()
                .getCurrentBatteryState().isAcCharging()
                || userPreferenceEnabled;
    }

    private void applyPerformanceMode(boolean userPreferenceEnabled) {
        try {
            mUpdateEngine.setPerformanceMode(shouldEnablePerformanceMode(userPreferenceEnabled));
        } catch (Throwable e) {
            Log.w(TAG, "Could not set performance mode", e);
        }
    }

    private ABUpdateInstaller(Context context, UpdaterController updaterController,
            UserPreferencesRepository userPreferencesRepository) {
        mUpdaterController = updaterController;
        mUserPreferencesRepository = userPreferencesRepository;
        mContext = context.getApplicationContext();
        mUpdateEngine = new UpdateEngine();
        mWakeLock = mContext.getSystemService(PowerManager.class).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "Updater:ABUpdateInstaller");
        mWakeLock.setReferenceCounted(false);
    }

    static synchronized ABUpdateInstaller getInstance(Context context,
            UpdaterController updaterController,
            UserPreferencesRepository userPreferencesRepository) {
        if (sInstance == null) {
            sInstance = new ABUpdateInstaller(context, updaterController,
                    userPreferencesRepository);
        }
        return sInstance;
    }

    public void install(String downloadId) {
        if (isInstallingUpdate(mContext)) {
            Log.e(TAG, "Already installing an update");
            return;
        }

        mDownloadId = downloadId;

        File file = mUpdaterController.getUpdate(mDownloadId).getFile();
        install(file, downloadId);
    }

    public void install(File file, String downloadId) {
        if (!file.exists()) {
            Log.e(TAG, "The given update doesn't exist");
            mUpdaterController.markInstallationFailed(downloadId);
            mUpdaterController.notifyUpdateChange(downloadId);
            return;
        }

        long offset;
        String[] headerKeyValuePairs;
        try {
            ZipFile zipFile = new ZipFile(file);
            offset = Utils.getZipEntryOffset(zipFile, Constants.AB_PAYLOAD_BIN_PATH);
            ZipEntry payloadPropEntry = zipFile.getEntry(Constants.AB_PAYLOAD_PROPERTIES_PATH);
            try (InputStream is = zipFile.getInputStream(payloadPropEntry);
                 InputStreamReader isr = new InputStreamReader(is);
                 BufferedReader br = new BufferedReader(isr)) {
                List<String> lines = new ArrayList<>();
                for (String line; (line = br.readLine()) != null;) {
                    lines.add(line);
                }
                headerKeyValuePairs = new String[lines.size()];
                headerKeyValuePairs = lines.toArray(headerKeyValuePairs);
            }
            zipFile.close();
        } catch (IOException | IllegalArgumentException e) {
            Log.e(TAG, "Could not prepare " + file, e);
            mUpdaterController.markInstallationFailed(downloadId);
            mUpdaterController.notifyUpdateChange(downloadId);
            return;
        }

        mStreaming = false;
        String zipFileUri = "file://" + file.getAbsolutePath();
        applyUpdate(zipFileUri, offset, 0, headerKeyValuePairs);
    }

    public void installStreaming(String downloadId) {
        if (isInstallingUpdate(mContext)) {
            Log.e(TAG, "Already installing an update");
            return;
        }

        mDownloadId = downloadId;
        mStreaming = true;

        Update update = mUpdaterController.getUpdate(mDownloadId);
        String downloadUrl = update.getDownloadUrl();

        new Thread(() -> {
            try {
                String[] headerKeyValuePairs = fetchPayloadProperties(downloadUrl,
                        update.getPayloadPropertiesOffset(),
                        update.getPayloadPropertiesSize());
                applyUpdate(downloadUrl, update.getPayloadOffset(),
                        update.getPayloadSize(), headerKeyValuePairs);
            } catch (IOException | ServiceSpecificException e) {
                Log.e(TAG, "Could not prepare streaming update", e);
                mUpdaterController.markInstallationFailed(downloadId);
                mUpdaterController.notifyUpdateChange(downloadId);
            }
        }, "UpdaterStreamingInstall").start();
    }

    int[] getStageWeights() {
        return mStreaming ? STAGE_WEIGHTS_STREAMING : STAGE_WEIGHTS;
    }

    private String[] fetchPayloadProperties(String downloadUrl, long offset, long size)
            throws IOException {
        SingleRangeHttpFetcher fetcher = new SingleRangeHttpFetcher(downloadUrl);
        byte[] data = fetcher.download(offset, size);
        return new String(data, StandardCharsets.UTF_8).split("\n");
    }

    private void applyUpdate(String url, long offset, long size,
            String[] headerKeyValuePairs) {
        if (!mBound) {
            mBound = mUpdateEngine.bind(mUpdateEngineCallback);
            if (!mBound) {
                Log.e(TAG, "Could not bind");
                mUpdaterController.markInstallationFailed(mDownloadId);
                mUpdaterController.notifyUpdateChange(mDownloadId);
                return;
            }
        }

        applyPerformanceMode(mUserPreferencesRepository.getAbPerfModeBlocking());

        try {
            mUpdateEngine.applyPayload(url, offset, size, headerKeyValuePairs);
        } catch (ServiceSpecificException e) {
            if (e.errorCode == 66 /* kUpdateAlreadyInstalled */) {
                installationDone(true);
                Update update = mUpdaterController.getUpdate(mDownloadId);
                mUpdaterController.setUpdate(mDownloadId,
                        update.withStatus(UpdateStatus.UPDATED_NEED_REBOOT));
                mUpdaterController.notifyUpdateChange(mDownloadId);
                return;
            }
            throw e;
        }
        acquireWakeLock();

        Update update = mUpdaterController.getUpdate(mDownloadId);
        mUpdaterController.setUpdate(mDownloadId,
                update.withStatus(UpdateStatus.INSTALLING));
        mUpdaterController.notifyUpdateChange(mDownloadId);

        PreferenceManager.getDefaultSharedPreferences(mContext).edit()
                .putString(PREF_INSTALLING_AB_ID, mDownloadId)
                .apply();

    }

    public void reconnect() {
        if (!isInstallingUpdate(mContext)) {
            Log.e(TAG, "reconnect: Not installing any update");
            return;
        }

        if (mBound) {
            return;
        }

        mDownloadId = PreferenceManager.getDefaultSharedPreferences(mContext)
                .getString(PREF_INSTALLING_AB_ID, null);

        // We will get a status notification as soon as we are connected
        mBound = mUpdateEngine.bind(mUpdateEngineCallback);
        if (!mBound) {
            Log.e(TAG, "Could not bind");
            return;
        }

        if (mDownloadId != null && !isInstallingUpdateSuspended(mContext)) {
            acquireWakeLock();
        }

        applyPerformanceMode(mUserPreferencesRepository.getAbPerfModeBlocking());
    }

    private void acquireWakeLock() {
        if (mUserPreferencesRepository.getAbWakeLockBlocking()) {
            mWakeLock.acquire(WAKELOCK_TIMEOUT);
        }
    }

    private void installationDone(boolean needsReboot) {
        mWakeLock.release();
        String id = needsReboot ? mDownloadId : null;
        PreferenceManager.getDefaultSharedPreferences(mContext).edit()
                .putString(Constants.PREF_NEEDS_REBOOT_ID, id)
                .putInt(PREF_NEEDS_REBOOT_BOOT_COUNT, getBootCount(mContext))
                .remove(PREF_INSTALLING_AB_ID)
                .apply();
    }

    public void cancel() {
        if (!isInstallingUpdate(mContext)) {
            Log.e(TAG, "cancel: Not installing any update");
            return;
        }

        if (!mBound) {
            Log.e(TAG, "Not connected to update engine");
            return;
        }

        mUpdateEngine.cancel();
        installationDone(false);

        Update update = mUpdaterController.getUpdate(mDownloadId);
        mUpdaterController.setUpdate(mDownloadId,
                update.withStatus(UpdateStatus.INSTALLATION_CANCELLED));
        mUpdaterController.notifyUpdateChange(mDownloadId);

    }

    public void suspend() {
        if (!isInstallingUpdate(mContext)) {
            Log.e(TAG, "cancel: Not installing any update");
            return;
        }

        if (!mBound) {
            Log.e(TAG, "Not connected to update engine");
            return;
        }

        mUpdateEngine.suspend();
        mWakeLock.release();

        Update update = mUpdaterController.getUpdate(mDownloadId);
        mUpdaterController.setUpdate(mDownloadId,
                update.withStatus(UpdateStatus.INSTALLATION_SUSPENDED));
        mUpdaterController.notifyUpdateChange(mDownloadId);

        PreferenceManager.getDefaultSharedPreferences(mContext).edit()
                .putString(PREF_INSTALLING_SUSPENDED_AB_ID, mDownloadId)
                .apply();

    }

    public void resume() {
        if (!isInstallingUpdateSuspended(mContext)) {
            Log.e(TAG, "cancel: No update is suspended");
            return;
        }

        if (!mBound) {
            Log.e(TAG, "Not connected to update engine");
            return;
        }

        mUpdateEngine.resume();
        acquireWakeLock();

        Update update = mUpdaterController.getUpdate(mDownloadId);
        mUpdaterController.setUpdate(mDownloadId, update.toBuilder()
                .setStatus(UpdateStatus.INSTALLING)
                .setInstallProgress(mProgress)
                .setFinalizing(mFinalizing)
                .build());
        mUpdaterController.notifyUpdateChange(mDownloadId);
        mUpdaterController.notifyInstallProgress(mDownloadId);

        PreferenceManager.getDefaultSharedPreferences(mContext).edit()
                .remove(PREF_INSTALLING_SUSPENDED_AB_ID)
                .apply();

    }
}
