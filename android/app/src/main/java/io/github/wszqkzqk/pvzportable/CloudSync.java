/*
 * Copyright (C) 2026 Zhou Qiankang <wszqkzqk@qq.com>
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * This file is part of PvZ-Portable.
 *
 * PvZ-Portable is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PvZ-Portable is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with PvZ-Portable. If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.wszqkzqk.pvzportable;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 自建云存档同步：启动时从服务器拉取比本机新的存档，退到后台时把 userdata 打包上传。
 * 服务器接口与网页版一致，按固定 key 存取一个 ZIP。
 */
public final class CloudSync {
    private static final String TAG = "PvZCloudSync";
    private static final String API = "http://115.159.125.254/pvz/api/save?key=9fe0f98f083fedae326b8a1ad9929ab0";
    private static final String PREFS = "pvz_cloud";
    private static final String PREF_MTIME = "last_mtime";
    private static final int CONNECT_TIMEOUT_MS = 6000;

    private CloudSync() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * 启动前同步拉取云端存档，最多等待 waitMs 毫秒，避免主线程长时间阻塞。
     */
    public static void restoreBlocking(Context ctx, File filesDir, long waitMs) {
        if (filesDir == null) return;
        Thread t = new Thread(() -> {
            try {
                restore(ctx, filesDir);
            } catch (Exception e) {
                Log.w(TAG, "restore failed", e);
            }
        }, "pvz-cloud-restore");
        t.start();
        try {
            t.join(waitMs);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static void restore(Context ctx, File filesDir) throws IOException {
        HttpURLConnection head = (HttpURLConnection) new URL(API).openConnection();
        head.setRequestMethod("HEAD");
        head.setConnectTimeout(CONNECT_TIMEOUT_MS);
        head.setReadTimeout(CONNECT_TIMEOUT_MS);
        head.setUseCaches(false);
        int headCode = head.getResponseCode();
        String mtime = head.getHeaderField("Last-Modified");
        head.disconnect();
        if (headCode != 200 || mtime == null) {
            return;
        }
        if (mtime.equals(prefs(ctx).getString(PREF_MTIME, ""))) {
            return;
        }

        HttpURLConnection get = (HttpURLConnection) new URL(API).openConnection();
        get.setConnectTimeout(CONNECT_TIMEOUT_MS);
        get.setReadTimeout(30000);
        get.setUseCaches(false);
        if (get.getResponseCode() != 200) {
            get.disconnect();
            return;
        }

        File userdata = new File(filesDir, "userdata");
        if (!userdata.exists() && !userdata.mkdirs()) {
            get.disconnect();
            return;
        }

        int count = 0;
        byte[] buf = new byte[16384];
        try (InputStream raw = get.getInputStream();
             ZipInputStream zin = new ZipInputStream(new BufferedInputStream(raw))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                while (name.startsWith("/")) name = name.substring(1);
                if (name.isEmpty() || name.contains("..")) continue;
                File out = new File(filesDir, name);
                if (entry.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                try (OutputStream fos = new BufferedOutputStream(new FileOutputStream(out))) {
                    int n;
                    while ((n = zin.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                    }
                }
                count++;
            }
        } finally {
            get.disconnect();
        }

        if (count > 0) {
            prefs(ctx).edit().putString(PREF_MTIME, mtime).apply();
            Log.i(TAG, "restored " + count + " files, mtime=" + mtime);
        }
    }

    public static void backupAsync(Context ctx, File filesDir) {
        if (filesDir == null) return;
        new Thread(() -> {
            try {
                backup(ctx, filesDir);
            } catch (Exception e) {
                Log.w(TAG, "backup failed", e);
            }
        }, "pvz-cloud-backup").start();
    }

    private static void backup(Context ctx, File filesDir) throws IOException {
        File userdata = new File(filesDir, "userdata");
        if (!userdata.isDirectory()) return;

        File tmp = new File(ctx.getCacheDir(), "pvz-cloud-save.zip");
        int count;
        try (ZipOutputStream zout = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(tmp)))) {
            count = zipDir(userdata, userdata, zout);
        }
        if (count == 0) {
            tmp.delete();
            return;
        }

        HttpURLConnection post = (HttpURLConnection) new URL(API).openConnection();
        post.setRequestMethod("POST");
        post.setDoOutput(true);
        post.setConnectTimeout(CONNECT_TIMEOUT_MS);
        post.setReadTimeout(30000);
        post.setFixedLengthStreamingMode(tmp.length());
        post.setRequestProperty("Content-Type", "application/zip");
        try (OutputStream os = post.getOutputStream();
             InputStream fis = new FileInputStream(tmp)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = fis.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        }
        int code = post.getResponseCode();
        String mtime = post.getHeaderField("Last-Modified");
        post.disconnect();
        tmp.delete();
        if (code == 200 && mtime != null) {
            prefs(ctx).edit().putString(PREF_MTIME, mtime).apply();
            Log.i(TAG, "backed up " + count + " files, mtime=" + mtime);
        } else {
            Log.w(TAG, "backup HTTP " + code);
        }
    }

    private static int zipDir(File dir, File base, ZipOutputStream zout) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int count = 0;
        byte[] buf = new byte[16384];
        for (File f : files) {
            if (f.isDirectory()) {
                count += zipDir(f, base, zout);
                continue;
            }
            String rel = f.getAbsolutePath().substring(base.getAbsolutePath().length() + 1)
                    .replace('\\', '/');
            zout.putNextEntry(new ZipEntry("userdata/" + rel));
            try (InputStream fis = new FileInputStream(f)) {
                int n;
                while ((n = fis.read(buf)) > 0) {
                    zout.write(buf, 0, n);
                }
            }
            zout.closeEntry();
            count++;
        }
        return count;
    }
}
