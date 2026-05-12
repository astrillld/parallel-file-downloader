package com.example.downloader;

@FunctionalInterface
public interface ProgressListener {
    void onProgress(long downloadedBytes, long totalBytes);
}