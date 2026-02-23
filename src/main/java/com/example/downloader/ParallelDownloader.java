package com.example.downloader;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;

public class ParallelDownloader {

    private final int threads;
    private final int chunkSizeBytes;

    public ParallelDownloader(int threads, int chunkSizeBytes) {
        if (threads <= 0) throw new IllegalArgumentException("threads must be > 0");
        if (chunkSizeBytes <= 0) throw new IllegalArgumentException("chunkSizeBytes must be > 0");
        this.threads = threads;
        this.chunkSizeBytes = chunkSizeBytes;
    }

    public void download(String url, Path outputFile) throws IOException, InterruptedException {
        HeadInfo info = head(url);

        if (!info.acceptRangesBytes) {
            throw new IOException("Server does not support byte ranges (Accept-Ranges: bytes).");
        }
        if (info.contentLength < 0) {
            throw new IOException("Missing/invalid Content-Length.");
        }

        List<Chunk> chunks = splitIntoChunks(info.contentLength, chunkSizeBytes);

        // Pre-allocate output file size
        try (RandomAccessFile raf = new RandomAccessFile(outputFile.toFile(), "rw")) {
            raf.setLength(info.contentLength);
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Void>> futures = new ArrayList<>();

        for (Chunk c : chunks) {
            futures.add(pool.submit(() -> {
                byte[] data = downloadChunk(url, c.startInclusive, c.endInclusive);
                writeAt(outputFile, c.startInclusive, data);
                return null;
            }));
        }

        pool.shutdown();

        // fail fast if any chunk fails
        for (Future<Void> f : futures) {
            try {
                f.get();
            } catch (ExecutionException e) {
                pool.shutdownNow();
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof IOException io) throw io;
                throw new IOException("Chunk download failed: " + cause.getMessage(), cause);
            }
        }

        if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
            pool.shutdownNow();
        }
    }

    private static void writeAt(Path outputFile, long offset, byte[] data) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(outputFile.toFile(), "rw")) {
            raf.seek(offset);
            raf.write(data);
        }
    }

    private static byte[] downloadChunk(String urlStr, long startInclusive, long endInclusive) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Range", "bytes=" + startInclusive + "-" + endInclusive);
        conn.setInstanceFollowRedirects(true);
        conn.connect();

        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_PARTIAL && code != HttpURLConnection.HTTP_OK) {
            throw new IOException("Unexpected response code for range GET: " + code);
        }

        int expectedLen = (int) (endInclusive - startInclusive + 1);

        try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
            byte[] buf = in.readAllBytes();
            // Some servers may return 200 OK with full content; we guard against that:
            if (buf.length != expectedLen) {
                // If full content came back, slice the needed part (rare, but safe)
                if (buf.length > expectedLen) {
                    byte[] slice = new byte[expectedLen];
                    System.arraycopy(buf, 0, slice, 0, expectedLen);
                    return slice;
                }
                throw new IOException("Chunk size mismatch.Expected " + expectedLen + " bytes, got " + buf.length);
            }
            return buf;
        } finally {
            conn.disconnect();
        }
    }

    private static HeadInfo head(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("HEAD");
        conn.setInstanceFollowRedirects(true);
        conn.connect();

        String acceptRanges = conn.getHeaderField("Accept-Ranges");
        String contentLength = conn.getHeaderField("Content-Length");

        boolean rangesBytes = acceptRanges != null && acceptRanges.toLowerCase(Locale.ROOT).contains("bytes");

        long len = -1;
        if (contentLength != null) {
            try {
                len = Long.parseLong(contentLength.trim());
            } catch (NumberFormatException ignored) {
                len = -1;
            }
        }

        conn.disconnect();
        return new HeadInfo(rangesBytes, len);
    }

    private static List<Chunk> splitIntoChunks(long totalBytes, int chunkSize) {
        List<Chunk> chunks = new ArrayList<>();
        long start = 0;
        while (start < totalBytes) {
            long end = Math.min(start + chunkSize - 1L, totalBytes - 1L);
            chunks.add(new Chunk(start, end));
            start = end + 1;
        }
        return chunks;
    }

    private record Chunk(long startInclusive, long endInclusive) {}
    private record HeadInfo(boolean acceptRangesBytes, long contentLength) {}
}