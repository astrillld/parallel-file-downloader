package com.example.downloader;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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

        if (!info.acceptRangesBytes()) {
            throw new IOException("Server does not support byte ranges (Accept-Ranges: bytes).");
        }
        if (info.contentLength() < 0) {
            throw new IOException("Missing or invalid Content-Length.");
        }

        List<Chunk> chunks = splitIntoChunks(info.contentLength(), chunkSizeBytes);

        // Pre-allocate output file size
        try (FileChannel channel = FileChannel.open(outputFile, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(ByteBuffer.allocate(1), info.contentLength() - 1);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<Void>> futures = new ArrayList<>(chunks.size());

            for (Chunk c : chunks) {
                futures.add(pool.submit(() -> {
                    byte[] data = downloadChunk(url, c.start(), c.end());
                    writeAt(channel, c.start(), data);
                    return null;
                }));
            }
            pool.shutdown();
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

        }
    }

    private static void writeAt(FileChannel channel, long offset, byte[] data) throws IOException {
        channel.write(ByteBuffer.wrap(data), offset);
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

        long expectedLen = endInclusive - startInclusive + 1;

        try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
            byte[] buf = in.readAllBytes();
            if (buf.length == expectedLen) {
                return buf;
            }
            // If the server replies 200 with full content, while Range was sent
            if (buf.length > expectedLen) {
                byte[] slice = new byte[(int) expectedLen];
                System.arraycopy(buf, (int) startInclusive, slice, 0, (int) expectedLen);
                return slice;
            }

            throw new IOException(
                    "Chunk size mismatch. Expected " + expectedLen + " bytes, got " + buf.length);
        } finally {
            conn.disconnect();
        }
    }

    private static HeadInfo head(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
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
            return new HeadInfo(rangesBytes, len);
        } finally {
            conn.disconnect();
        }
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

    record Chunk(long start, long end) {}
    private record HeadInfo(boolean acceptRangesBytes, long contentLength) {}
}