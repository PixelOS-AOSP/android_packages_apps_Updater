/*
 * Copyright (C) 2017-2022 The LineageOS Project
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
package net.pixelos.ota.controller;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import net.pixelos.ota.MirrorsDbHelper;
import net.pixelos.ota.UpdatesDbHelper;
import net.pixelos.ota.download.DownloadClient;
import net.pixelos.ota.misc.Constants;
import net.pixelos.ota.misc.Utils;
import net.pixelos.ota.model.Update;
import net.pixelos.ota.model.UpdateInfo;
import net.pixelos.ota.model.UpdateStatus;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class UpdaterController {

    public static final String ACTION_DOWNLOAD_PROGRESS = "action_download_progress";
    public static final String ACTION_INSTALL_PROGRESS = "action_install_progress";
    public static final String ACTION_UPDATE_REMOVED = "action_update_removed";
    public static final String ACTION_UPDATE_STATUS = "action_update_status_change";
    public static final String EXTRA_DOWNLOAD_ID = "extra_download_id";
    private static final int MAX_REPORT_INTERVAL_MS = 1000;
    private static final String TAG = "UpdaterController";
    private static UpdaterController sUpdaterController;
    private static MirrorsDbHelper sMirrorsDbHelper;
    private final Context mContext;
    private final LocalBroadcastManager mBroadcastManager;
    private final UpdatesDbHelper mUpdatesDbHelper;

    private final PowerManager.WakeLock mWakeLock;

    private final File mDownloadRoot;
    private final Set<String> mVerifyingUpdates = new HashSet<>();
    private static final Map<String, DownloadEntry> mDownloads = new HashMap<>();
    private int mActiveDownloads = 0;

    // Sourceforge mirror variables
    private static Map<String, String> sMirrorLinks;
    private static Map<Double, String> sRankedMirrors;
    private static Map<String, String> sSortedSfMirrors;
    public static Map<Double, String> sSortedRankedMirrors;

    private UpdaterController(Context context) {
        mBroadcastManager = LocalBroadcastManager.getInstance(context);
        mUpdatesDbHelper = new UpdatesDbHelper(context);
        sMirrorsDbHelper = MirrorsDbHelper.getInstance(context);
        mDownloadRoot = Utils.getDownloadPath(context);
        PowerManager powerManager = context.getSystemService(PowerManager.class);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Updater:wakelock");
        mWakeLock.setReferenceCounted(false);
        mContext = context.getApplicationContext();
        Utils.cleanupDownloadsDir(context);

        for (Update update : mUpdatesDbHelper.getUpdates()) {
            addUpdate(update, false);
        }
    }

    public static synchronized UpdaterController getInstance(Context context) {
        if (sUpdaterController == null) {
            sUpdaterController = new UpdaterController(context);
        }
        return sUpdaterController;
    }

    void notifyUpdateChange(String downloadId) {
        new Thread(
                () -> {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    Intent intent = new Intent();
                    intent.setAction(ACTION_UPDATE_STATUS);
                    intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
                    mBroadcastManager.sendBroadcast(intent);
                })
                .start();
    }

    void notifyUpdateDelete(String downloadId) {
        Intent intent = new Intent();
        intent.setAction(ACTION_UPDATE_REMOVED);
        intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
        mBroadcastManager.sendBroadcast(intent);
    }

    void notifyDownloadProgress(String downloadId) {
        Intent intent = new Intent();
        intent.setAction(ACTION_DOWNLOAD_PROGRESS);
        intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
        mBroadcastManager.sendBroadcast(intent);
    }

    void notifyInstallProgress(String downloadId) {
        Intent intent = new Intent();
        intent.setAction(ACTION_INSTALL_PROGRESS);
        intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
        mBroadcastManager.sendBroadcast(intent);
    }

    private void tryReleaseWakelock() {
        if (!hasActiveDownloads()) {
            mWakeLock.release();
        }
    }

    private void addDownloadClient(DownloadEntry entry, DownloadClient downloadClient) {
        if (entry.mDownloadClient != null) {
            return;
        }
        entry.mDownloadClient = downloadClient;
        mActiveDownloads++;
    }

    private void removeDownloadClient(DownloadEntry entry) {
        if (entry.mDownloadClient == null) {
            return;
        }
        entry.mDownloadClient = null;
        mActiveDownloads--;
    }

    private DownloadClient.DownloadCallback getDownloadCallback(final String downloadId) {
        return new DownloadClient.DownloadCallback() {

            @Override
            public void onResponse(@NonNull DownloadClient.Headers headers) {
                final DownloadEntry entry = mDownloads.get(downloadId);
                if (entry == null) {
                    return;
                }
                final Update update = entry.mUpdate;
                String contentLength = headers.get("Content-Length");
                if (contentLength != null) {
                    try {
                        long size = Long.parseLong(contentLength);
                        if (update.getFileSize() < size) {
                            update.setFileSize(size);
                        }
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "Could not get content-length");
                    }
                }
                update.setStatus(UpdateStatus.DOWNLOADING);
                update.setPersistentStatus(UpdateStatus.Persistent.INCOMPLETE);
                new Thread(
                        () ->
                                mUpdatesDbHelper.addUpdateWithOnConflict(
                                        update, SQLiteDatabase.CONFLICT_REPLACE))
                        .start();
                notifyUpdateChange(downloadId);
            }

            @Override
            public void onSuccess() {
                Log.d(TAG, "Download complete");
                DownloadEntry entry = mDownloads.get(downloadId);
                if (entry != null) {
                    Update update = entry.mUpdate;
                    update.setStatus(UpdateStatus.VERIFYING);
                    removeDownloadClient(entry);
                    verifyUpdateAsync(downloadId);
                    notifyUpdateChange(downloadId);
                    tryReleaseWakelock();
                }
            }

            @Override
            public void onFailure(boolean cancelled) {
                if (cancelled) {
                    Log.d(TAG, "Download cancelled");
                    // Already notified
                } else {
                    DownloadEntry entry = mDownloads.get(downloadId);
                    if (entry != null) {
                        Update update = entry.mUpdate;
                        Log.e(TAG, "Download failed");
                        removeDownloadClient(entry);
                        update.setStatus(UpdateStatus.PAUSED_ERROR);
                        notifyUpdateChange(downloadId);
                    }
                }
                tryReleaseWakelock();
            }
        };
    }

    private DownloadClient.ProgressListener getProgressListener(final String downloadId) {
        return new DownloadClient.ProgressListener() {
            private long mLastUpdate = 0;
            private int mProgress = 0;

            @Override
            public void update(long bytesRead, long contentLength, long speed, long eta) {
                DownloadEntry entry = mDownloads.get(downloadId);
                if (entry == null) {
                    return;
                }
                Update update = entry.mUpdate;
                if (contentLength <= 0) {
                    if (update.getFileSize() <= 0) {
                        return;
                    } else {
                        contentLength = update.getFileSize();
                    }
                }
                if (contentLength <= 0) {
                    return;
                }
                final long now = SystemClock.elapsedRealtime();
                int progress = Math.round(bytesRead * 100f / contentLength);
                if (progress != mProgress || mLastUpdate - now > MAX_REPORT_INTERVAL_MS) {
                    mProgress = progress;
                    mLastUpdate = now;
                    update.setProgress(progress);
                    update.setEta(eta);
                    update.setSpeed(speed);
                    notifyDownloadProgress(downloadId);
                }
            }
        };
    }

    @SuppressLint("SetWorldReadable")
    private void verifyUpdateAsync(final String downloadId) {
        mVerifyingUpdates.add(downloadId);
        new Thread(
                () -> {
                    DownloadEntry entry = mDownloads.get(downloadId);
                    if (entry != null) {
                        Update update = entry.mUpdate;
                        File file = update.getFile();
                        if (file.exists() && verifyPackage(file)) {
                            //noinspection ResultOfMethodCallIgnored
                            file.setReadable(true, false);
                            update.setPersistentStatus(UpdateStatus.Persistent.VERIFIED);
                            mUpdatesDbHelper.changeUpdateStatus(update);
                            update.setStatus(UpdateStatus.VERIFIED);
                        } else {
                            update.setPersistentStatus(UpdateStatus.Persistent.UNKNOWN);
                            mUpdatesDbHelper.removeUpdate(downloadId);
                            update.setProgress(0);
                            update.setStatus(UpdateStatus.VERIFICATION_FAILED);
                        }
                        mVerifyingUpdates.remove(downloadId);
                        notifyUpdateChange(downloadId);
                    }
                })
                .start();
    }

    private boolean verifyPackage(File file) {
        try {
            android.os.RecoverySystem.verifyPackage(file, null, null);
            Log.e(TAG, "Verification successful");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Verification failed", e);
            if (file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            } else {
                // The download was probably stopped. Exit silently
                Log.e(TAG, "Error while verifying the file", e);
            }
            return false;
        }
    }

    private boolean fixUpdateStatus(Update update) {
        switch (update.getPersistentStatus()) {
            case UpdateStatus.Persistent.VERIFIED:
            case UpdateStatus.Persistent.INCOMPLETE:
                if (update.getFile() == null || !update.getFile().exists()) {
                    update.setStatus(UpdateStatus.UNKNOWN);
                    return false;
                } else if (update.getFileSize() > 0) {
                    update.setStatus(UpdateStatus.PAUSED);
                    int progress =
                            Math.round(update.getFile().length() * 100f / update.getFileSize());
                    update.setProgress(progress);
                }
                break;
        }
        return true;
    }

    public void setUpdatesAvailableOnline(List<String> downloadIds, boolean purgeList) {
        List<String> toRemove = new ArrayList<>();
        for (DownloadEntry entry : mDownloads.values()) {
            boolean online = downloadIds.contains(entry.mUpdate.getDownloadId());
            entry.mUpdate.setAvailableOnline(online);
            if (!online
                    && purgeList
                    && entry.mUpdate.getPersistentStatus() == UpdateStatus.Persistent.UNKNOWN) {
                toRemove.add(entry.mUpdate.getDownloadId());
            }
        }
        for (String downloadId : toRemove) {
            Log.d(TAG, downloadId + " no longer available online, removing");
            mDownloads.remove(downloadId);
            notifyUpdateDelete(downloadId);
            sMirrorsDbHelper.delUpdate(downloadId);
        }
    }

    public boolean addUpdate(UpdateInfo update) {
        return addUpdate(update, true);
    }

    public boolean addUpdate(final UpdateInfo updateInfo, boolean availableOnline) {
        Log.d(TAG, "Adding download: " + updateInfo.getDownloadId());
        if (mDownloads.containsKey(updateInfo.getDownloadId())) {
            Log.d(TAG, "Download (" + updateInfo.getDownloadId() + ") already added");
            DownloadEntry entry = mDownloads.get(updateInfo.getDownloadId());
            if (entry != null) {
                Update updateAdded = entry.mUpdate;
                updateAdded.setAvailableOnline(availableOnline && updateAdded.getAvailableOnline());
                // Check if there's a saved mirror URL
                String mirrorUrl = sMirrorsDbHelper.getMirrorUrl(updateInfo.getDownloadId());
                if (mirrorUrl != null && !mirrorUrl.isEmpty()) {
                    updateAdded.setDownloadUrl(mirrorUrl);
                    Log.d(TAG, "Using previous mirror: " + mirrorUrl);
                } else {
                    updateAdded.setDownloadUrl(updateInfo.getDownloadUrl());
                    Log.d(TAG, "Using default server url: " + updateInfo.getDownloadUrl());
                }
            }
            return false;
        }
        Update update = new Update(updateInfo);
        if (!fixUpdateStatus(update) && !availableOnline) {
            update.setPersistentStatus(UpdateStatus.Persistent.UNKNOWN);
            deleteUpdateAsync(update);
            Log.d(TAG, update.getDownloadId() + " had an invalid status and is not online");
            return false;
        }
        update.setAvailableOnline(availableOnline);
        mDownloads.put(update.getDownloadId(), new DownloadEntry(update));
        // Add to mirrors database if not exists
        if (!sMirrorsDbHelper.isUpdateExists(updateInfo.getDownloadId())) {
            sMirrorsDbHelper.setUpdate(updateInfo.getDownloadId());
            Log.d(TAG, "Adding new update to mirrors database: " + update.getDownloadId());
        } else {
            // Set previous mirror url if update already exists in mirrorsDB
            String mirrorUrl = sMirrorsDbHelper.getMirrorUrl(updateInfo.getDownloadId());
            if (mirrorUrl != null && !mirrorUrl.isEmpty()) {
                update.setDownloadUrl(mirrorUrl);
                Log.d(TAG, "Setting previous mirror: " + mirrorUrl);
            }
        }
        return true;
    }

    @SuppressLint("WakelockTimeout")
    public void startDownload(String downloadId) {
        Log.d(TAG, "Starting " + downloadId);
        if (!mDownloads.containsKey(downloadId) || isDownloading(downloadId)) {
            return;
        }
        DownloadEntry entry = mDownloads.get(downloadId);
        if (entry == null) {
            Log.e(TAG, "Could not get download entry");
            return;
        }
        Update update = entry.mUpdate;
        File destination = new File(mDownloadRoot, update.getName());
        if (destination.exists()) {
            destination = Utils.appendSequentialNumber(destination);
            Log.d(TAG, "Changing name with " + destination.getName());
        }
        update.setFile(destination);
        DownloadClient downloadClient;
        try {
            downloadClient =
                    new DownloadClient.Builder()
                            .setUrl(update.getDownloadUrl())
                            .setDestination(update.getFile())
                            .setDownloadCallback(getDownloadCallback(downloadId))
                            .setProgressListener(getProgressListener(downloadId))
                            .setUseDuplicateLinks(true)
                            .build();
        } catch (IOException exception) {
            Log.e(TAG, "Could not build download client");
            update.setStatus(UpdateStatus.PAUSED_ERROR);
            notifyUpdateChange(downloadId);
            return;
        }
        addDownloadClient(entry, downloadClient);
        update.setStatus(UpdateStatus.STARTING);
        notifyUpdateChange(downloadId);
        downloadClient.start();
        mWakeLock.acquire();
    }

    @SuppressLint("WakelockTimeout")
    public void resumeDownload(String downloadId) {
        Log.d(TAG, "Resuming " + downloadId);
        if (!mDownloads.containsKey(downloadId) || isDownloading(downloadId)) {
            return;
        }
        DownloadEntry entry = mDownloads.get(downloadId);
        if (entry == null) {
            Log.e(TAG, "Could not get download entry");
            return;
        }
        Update update = entry.mUpdate;
        File file = update.getFile();
        if (file == null || !file.exists()) {
            Log.e(TAG, "The destination file of " + downloadId + " doesn't exist, can't resume");
            update.setStatus(UpdateStatus.PAUSED_ERROR);
            notifyUpdateChange(downloadId);
            return;
        }
        if (file.exists() && update.getFileSize() > 0 && file.length() >= update.getFileSize()) {
            Log.d(TAG, "File already downloaded, starting verification");
            update.setStatus(UpdateStatus.VERIFYING);
            verifyUpdateAsync(downloadId);
            notifyUpdateChange(downloadId);
        } else {
            DownloadClient downloadClient;
            try {
                downloadClient =
                        new DownloadClient.Builder()
                                .setUrl(update.getDownloadUrl())
                                .setDestination(update.getFile())
                                .setDownloadCallback(getDownloadCallback(downloadId))
                                .setProgressListener(getProgressListener(downloadId))
                                .setUseDuplicateLinks(true)
                                .build();
            } catch (IOException exception) {
                Log.e(TAG, "Could not build download client");
                update.setStatus(UpdateStatus.PAUSED_ERROR);
                notifyUpdateChange(downloadId);
                return;
            }
            addDownloadClient(entry, downloadClient);
            update.setStatus(UpdateStatus.STARTING);
            notifyUpdateChange(downloadId);
            downloadClient.resume();
            mWakeLock.acquire();
        }
    }

    public void pauseDownload(String downloadId) {
        Log.d(TAG, "Pausing " + downloadId);
        if (!isDownloading(downloadId)) {
            return;
        }

        DownloadEntry entry = mDownloads.get(downloadId);
        if (entry != null) {
            entry.mDownloadClient.cancel();
            removeDownloadClient(entry);
            entry.mUpdate.setStatus(UpdateStatus.PAUSED);
            entry.mUpdate.setEta(0);
            entry.mUpdate.setSpeed(0);
            notifyUpdateChange(downloadId);
        }
    }

    private void deleteUpdateAsync(final Update update) {
        new Thread(
                () -> {
                    File file = update.getFile();
                    if (file.exists() && !file.delete()) {
                        Log.e(TAG, "Could not delete " + file.getAbsolutePath());
                    }
                    mUpdatesDbHelper.removeUpdate(update.getDownloadId());
                })
                .start();
    }

    public void deleteUpdate(String downloadId) {
        Log.d(TAG, "Deleting update: " + downloadId);
        if (!mDownloads.containsKey(downloadId) || isDownloading(downloadId)) {
            return;
        }
        DownloadEntry entry = mDownloads.get(downloadId);
        if (entry != null) {
            Update update = entry.mUpdate;
            update.setStatus(UpdateStatus.DELETED);
            update.setProgress(0);
            update.setPersistentStatus(UpdateStatus.Persistent.UNKNOWN);
            deleteUpdateAsync(update);

            final boolean isLocalUpdate = Update.LOCAL_ID.equals(downloadId);
            if (!isLocalUpdate && !update.getAvailableOnline()) {
                Log.d(TAG, "Download no longer available online, removing");
                mDownloads.remove(downloadId);
                notifyUpdateDelete(downloadId);
                sMirrorsDbHelper.delUpdate(downloadId);
            } else {
                notifyUpdateChange(downloadId);
            }
        }
    }

    public List<UpdateInfo> getUpdates() {
        List<UpdateInfo> updates = new ArrayList<>();
        for (DownloadEntry entry : mDownloads.values()) {
            updates.add(entry.mUpdate);
        }
        return updates;
    }

    public UpdateInfo getUpdate(String downloadId) {
        DownloadEntry entry = mDownloads.get(downloadId);
        return entry != null ? entry.mUpdate : null;
    }

    Update getActualUpdate(String downloadId) {
        DownloadEntry entry = mDownloads.get(downloadId);
        return entry != null ? entry.mUpdate : null;
    }

    public boolean isDownloading(String downloadId) {
        //noinspection ConstantConditions
        return mDownloads.containsKey(downloadId)
                && mDownloads.get(downloadId).mDownloadClient != null;
    }

    public boolean hasActiveDownloads() {
        return mActiveDownloads > 0;
    }

    public boolean isVerifyingUpdate() {
        return !mVerifyingUpdates.isEmpty();
    }

    public boolean isVerifyingUpdate(String downloadId) {
        return mVerifyingUpdates.contains(downloadId);
    }

    public boolean isInstallingUpdate() {
        return UpdateInstaller.isInstalling() || ABUpdateInstaller.isInstallingUpdate(mContext);
    }

    public boolean isInstallingUpdate(String downloadId) {
        return UpdateInstaller.isInstalling(downloadId)
                || ABUpdateInstaller.isInstallingUpdate(mContext, downloadId);
    }

    public boolean isInstallingABUpdate() {
        return ABUpdateInstaller.isInstallingUpdate(mContext);
    }

    public boolean isWaitingForReboot(String downloadId) {
        return ABUpdateInstaller.isWaitingForReboot(mContext, downloadId);
    }

    public void setPerformanceMode(boolean enable) {
        if (!Utils.isABDevice()) {
            return;
        }
        ABUpdateInstaller.getInstance(mContext, this).setPerformanceMode(enable);
    }

    // SourceForge mirror methods

    public static void setSfMirror(UpdateInfo updateInfo, Context context, String mirror) {
        if (mDownloads.containsKey(updateInfo.getDownloadId())) {
            DownloadEntry entry = mDownloads.get(updateInfo.getDownloadId());
            if (entry == null) return;
            Update updateAdded = entry.mUpdate;
            // Default to server URL if mirror not found
            String mirrorUrl = updateInfo.getDownloadUrl();

            if (sSortedSfMirrors != null && !sSortedSfMirrors.isEmpty()) {
                for (Map.Entry<String, String> sortedMirrors : sSortedSfMirrors.entrySet()) {
                    if (mirror.equals(sortedMirrors.getKey())) {
                        mirrorUrl = sortedMirrors.getValue();
                        sMirrorsDbHelper.setMirrorUrl(sortedMirrors.getValue(), updateInfo.getDownloadId());
                        break;
                    }
                }
            } else if (sMirrorLinks != null && !sMirrorLinks.isEmpty()) {
                for (Map.Entry<String, String> sfMirrors : sMirrorLinks.entrySet()) {
                    if (mirror.equals(sfMirrors.getKey())) {
                        mirrorUrl = sfMirrors.getValue();
                        sMirrorsDbHelper.setMirrorUrl(sfMirrors.getValue(), updateInfo.getDownloadId());
                        break;
                    }
                }
            }

            updateAdded.setDownloadUrl(mirrorUrl);
            sMirrorsDbHelper.setMirrorName(mirror, updateInfo.getDownloadId());
            Log.d(TAG, "Mirror for: " + updateInfo.getName() + " set to " + mirrorUrl);
        }
    }

    public static Map<String, String> sourceforgeMirrors(UpdateInfo update) {
        sMirrorLinks = new LinkedHashMap<>();
        sRankedMirrors = new LinkedHashMap<>();
        sSortedSfMirrors = new LinkedHashMap<>();
        Map<String, String> rankLinks = new LinkedHashMap<>();

        String downloadUrl = update.getDownloadUrl();
        if (downloadUrl == null || downloadUrl.isEmpty()) {
            Log.e(TAG, "Cannot fetch mirrors: download URL is null or empty");
            return null;
        }

        // Parse SourceForge URL to extract project name and filepath
        // Format 1: https://sourceforge.net/projects/{project}/files/{filepath}
        // Format 2: https://sourceforge.net/projects/{project}/files/{filepath}/download
        // Format 3: https://{mirror}.dl.sourceforge.net/project/{project}/{filepath}
        String projectName = null;
        String filepath = null;

        try {
            if (downloadUrl.contains("sourceforge.net/projects/")) {
                // Format: https://sourceforge.net/projects/{project}/files/{filepath}[/download]
                int projectStart = downloadUrl.indexOf("/projects/") + 10;
                int projectEnd = downloadUrl.indexOf("/files/", projectStart);
                if (projectEnd == -1) projectEnd = downloadUrl.indexOf("/", projectStart);
                projectName = downloadUrl.substring(projectStart, projectEnd);

                int filesStart = downloadUrl.indexOf("/files/") + 6; // Keep the leading /
                String remaining = downloadUrl.substring(filesStart);
                // Remove trailing /download if present
                if (remaining.endsWith("/download")) {
                    remaining = remaining.substring(0, remaining.length() - 9);
                }
                filepath = remaining;
            } else if (downloadUrl.contains(".dl.sourceforge.net/project/")) {
                // Format: https://{mirror}.dl.sourceforge.net/project/{project}/{filepath}
                int projectStart = downloadUrl.indexOf("/project/") + 9;
                int projectEnd = downloadUrl.indexOf("/", projectStart);
                projectName = downloadUrl.substring(projectStart, projectEnd);
                filepath = downloadUrl.substring(projectStart + projectName.length());
            }
        } catch (StringIndexOutOfBoundsException e) {
            Log.e(TAG, "Failed to parse SourceForge URL: " + downloadUrl, e);
            return null;
        }

        if (projectName == null || filepath == null) {
            Log.e(TAG, "Could not extract project/filepath from URL: " + downloadUrl);
            Log.e(TAG, "URL must be a SourceForge download link");
            return null;
        }

        Log.d(TAG, "Parsed from URL - Project: " + projectName + ", Filepath: " + filepath);

        String mirrorsUrl = "https://sourceforge.net/settings/mirror_choices?projectname=" + projectName + "&filename=" + filepath;
        Log.d(TAG, "Fetching mirrors from: " + mirrorsUrl);

        final String finalProjectName = projectName;
        final String finalFilepath = filepath;
        final Exception[] fetchException = {null};

        Thread mirrorFetch = new Thread(() -> {
            try {
                Log.d(TAG, "Starting mirror fetch request...");
                Document doc = Jsoup.connect(mirrorsUrl)
                        .userAgent("Mozilla/5.0")
                        .timeout(15000)
                        .get();
                Elements links = doc.select("#mirrorList li");

                Log.d(TAG, "Found " + links.size() + " mirror elements");

                if (links.isEmpty()) {
                    Log.w(TAG, "No mirrors found in response. HTML snippet: " +
                            doc.body().html().substring(0, Math.min(500, doc.body().html().length())));
                }

                for (Element link : links) {
                    String mirrorName = link.attr("id");
                    String mirrorPlace = link.text();
                    if (!mirrorName.equals("autoselect")) {
                        try {
                            mirrorPlace = mirrorPlace.substring(mirrorPlace.lastIndexOf("(") + 1,
                                            mirrorPlace.lastIndexOf(")"))
                                    .split(",", 2)[0]
                                    .trim();
                            sMirrorLinks.put(mirrorPlace, "https://" + mirrorName + ".dl.sourceforge.net/project/" + finalProjectName + finalFilepath);
                            rankLinks.put(mirrorPlace, mirrorName + ".dl.sourceforge.net");
                            Log.d(TAG, "Mirror: " + mirrorName + " (" + mirrorPlace + ")");
                        } catch (StringIndexOutOfBoundsException e) {
                            Log.w(TAG, "Failed to parse mirror place for: " + mirrorName + ", text: " + mirrorPlace);
                        }
                    }
                }
                Log.d(TAG, "Successfully parsed " + sMirrorLinks.size() + " mirrors");
            } catch (IOException e) {
                Log.e(TAG, "Failed to fetch sourceforge mirrors: " + e.getMessage(), e);
                fetchException[0] = e;
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error fetching mirrors: " + e.getMessage(), e);
                fetchException[0] = e;
            }
        });

        try {
            mirrorFetch.start();
            mirrorFetch.join();

            if (fetchException[0] != null) {
                Log.e(TAG, "Mirror fetch failed with exception: " + fetchException[0].getMessage());
                return null;
            }

            if (sMirrorLinks.isEmpty()) {
                Log.w(TAG, "No mirrors were found");
                return null;
            }

            return cookRankMirrorsData(rankLinks);
        } catch (InterruptedException e) {
            Log.e(TAG, "Mirror fetch thread interrupted!", e);
            return null;
        }
    }

    private static Map<String, String> cookRankMirrorsData(Map<String, String> rankLinks) {
        ExecutorService executor = Executors.newCachedThreadPool();

        for (Map.Entry<String, String> rlinks : rankLinks.entrySet()) {
            String rankUrl = rlinks.getValue();
            String rankName = rlinks.getKey();
            executor.execute(new RankMirrors(rankUrl, rankName));
        }

        executor.shutdown();
        try {
            executor.awaitTermination(30, TimeUnit.SECONDS);
            return sortMirrors(sRankedMirrors);
        } catch (InterruptedException e) {
            Log.e(TAG, "Executor interrupted!", e);
            return null;
        }
    }

    private static Map<String, String> sortMirrors(Map<Double, String> rankedMirrors) {
        sSortedRankedMirrors = new TreeMap<>(rankedMirrors);
        for (Map.Entry<Double, String> rankLinks : sSortedRankedMirrors.entrySet()) {
            for (Map.Entry<String, String> mirrorLinks : sMirrorLinks.entrySet()) {
                if (rankLinks.getValue().equals(mirrorLinks.getKey())) {
                    sSortedSfMirrors.put(rankLinks.getValue(), mirrorLinks.getValue());
                    Log.d(TAG, "Sorted mirrors list: " + rankLinks.getValue());
                }
            }
        }
        return sSortedSfMirrors;
    }

    private static class RankMirrors implements Runnable {
        private final String rankUrl;
        private final String rankName;

        RankMirrors(String rankUrl, String rankName) {
            this.rankUrl = rankUrl;
            this.rankName = rankName;
        }

        @Override
        public void run() {
            try {
                String[] pingCmd = {"ping", "-c", "5", rankUrl};
                String pingOutput;
                double pingResult;
                Runtime runtime = Runtime.getRuntime();
                Process process = runtime.exec(pingCmd);
                BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()));
                pingOutput = in.readLine();

                while (pingOutput != null) {
                    if (pingOutput.contains("rtt") || pingOutput.contains("round-trip")) {
                        // Parse mdev value from ping output
                        int lastSlash = pingOutput.lastIndexOf("/");
                        int msIndex = pingOutput.lastIndexOf(" ms");
                        if (lastSlash != -1 && msIndex != -1 && lastSlash < msIndex) {
                            try {
                                pingResult = Double.parseDouble(pingOutput.substring(lastSlash + 1, msIndex).trim());
                                if (pingResult != 0) {
                                    sRankedMirrors.put(pingResult, rankName);
                                }
                                Log.d(TAG, "mdev of sourceforge mirror " + rankName + ": " + pingResult);
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                    pingOutput = in.readLine();
                }
                in.close();
            } catch (IOException e) {
                Log.e(TAG, "Failed to rank sourceforge mirror " + rankName, e);
            }
        }
    }

    public static MirrorsDbHelper getMirrorsDbHelper() {
        return sMirrorsDbHelper;
    }

    private static class DownloadEntry {
        final Update mUpdate;
        DownloadClient mDownloadClient;

        private DownloadEntry(Update update) {
            mUpdate = update;
        }
    }
}
