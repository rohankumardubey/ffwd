/*-
 * -\-\-
 * FastForward Agent
 * --
 * Copyright (C) 2016 - 2019 Spotify AB
 * --
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
 * -/-/-
 */

package com.spotify.metrics.dashboard.writer;

import static com.spotify.ffwd.grafana.common.Consts.bucketName;

import com.google.api.gax.paging.Page;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Responsible for:<p></p>
 *
 * 1. finding the most recent JSON grafana tags file<p></p>
 * 2. parsing it into a Set<String><p></p>
 * 3. stores it as a public static, atomic reference for easy access by client
 * code<p></p>
 *
 * This class will be used directly in ffwd
 */
public class GrafanaTagReader {
    private static Storage storage = StorageOptions.getDefaultInstance().getService();
    private static String projectId = getGcpProject();
    private static String THREAD_NAME = "ffwd-grafana-tag-reader-thread";

    private static boolean isStarted = false;

    // Effectively stores a reference to the background thread that is periodically
    // fetching new tags from Cloud Storage.
    private static ScheduledFuture<?> workFuture;

    /**
     * needs to be called during ffwd init
     */
    public synchronized static boolean start() {
        if (isStarted)
            return false;

        isStarted = true;

        periodicallyRefreshGrafanaTags();

        return true;
    }

    public synchronized static void stop() {
        if (!isStarted)
            return;

        isStarted = false;

        try {
            workFuture.cancel(true);
        } catch (Exception e) {
            e.printStackTrace();
            // do something sensible
        }
    }

    private static void periodicallyRefreshGrafanaTags() {

        Runnable target = new Runnable() {
            @Override
            public void run() {
                try {
                    updateGrafanaTags();
                } catch (Exception e) {
                    e.printStackTrace();
                    // do something sensible
                }
            }
        };

        ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);
        workFuture = executorService.scheduleAtFixedRate(target, 5, 10,
                TimeUnit.MINUTES);

        return;
    }

    private static String getGcpProject() {
        // TODO
        return null;
    }

    private static AtomicReference<Set<String>> usedGrafanaTags =
            new AtomicReference<>(new HashSet<String>());

    private static void updateGrafanaTags() {
        Blob latestFile = downloadNewGrafanaTagFile();

        if (latestFile == null) {
            // TODO do something sensible
            return;
        }

        byte[] content = latestFile.getContent();
        Set<String> latestTags = convertBytesToStringSet(content);
        usedGrafanaTags.set(latestTags);
    }

    private static Set<String> convertBytesToStringSet(byte[] content) {
        // TODO this simple approach might work. I doubt it tho.
        //        Object o = content;
        //        Set<String> result = (Set<String>) o;
        // return result
        //
        // Failing the above, do the below
        //
        ByteArrayInputStream bytesIn = new ByteArrayInputStream(content);
        Set<String> tagSet = null;
        try {
            ObjectInputStream ois = new ObjectInputStream(bytesIn);
            Object obj = ois.readObject();
            ois.close();
            tagSet  = (Set<String>) obj;
        } catch (IOException e) {
            e.printStackTrace();
            // TODO do something sensible
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            // TODO do something sensible
        }

        return tagSet;
    }

    private static Blob downloadNewGrafanaTagFile() {
        try {
            Bucket bucket = storage.get(bucketName);
            Page<Blob> blobs = bucket.list();

            return findLatestTagsFile(blobs);

        } catch (Exception exc) {
            // TODO do something sensible
            exc.printStackTrace();
            return null;
        }
    }

    private static Blob findLatestTagsFile(Page<Blob> blobs) {
        Date latestFile = new Date(0);  // very old
        Blob latestBlob = null;
        for (Blob blob : blobs.iterateAll()) {
            System.out.println(blob.getName());
            Date fileDate = new Date(blob.getCreateTime());
            System.out.println("TimeCreated: " + fileDate);
            if (fileDate.before(latestFile)) {
                latestFile = fileDate;
                latestBlob = blob;
            }
        }

        return latestBlob;
    }

    public static boolean areUsedTags(String what, String role) {
        String pair = what + "-" + role;

        return usedGrafanaTags.get().contains(pair);
    }
}
