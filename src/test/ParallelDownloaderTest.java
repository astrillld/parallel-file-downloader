package com.example.downloader;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class ParallelDownloaderTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void downloadsFileInParallelAndMatchesContent() throws Exception {
        byte[] content = generateData(2_500_000); // 2.5 MB
        startRangeServer(content);

        String url = "http://localhost:" + server.getAddress().getPort() + "/file.bin";

        Path out = Files.createTempFile("downloaded-", ".bin");
        out.toFile().deleteOnExit();

        ParallelDownloader downloader = new ParallelDownloader(4, 256_000);
        downloader.download(url, out);

        byte[] downloaded = Files.readAllBytes(out);

        assertArrayEquals(hash(content), hash(downloaded), "Downloaded file hash must match source");
        assertEquals(content.length, downloaded.length, "Downloaded length must match");
        assertTrue(Arrays.equals(content, downloaded), "Downloaded bytes must match exactly");
    }

    private void startRangeServer(byte[] content) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/file.bin", exchange -> handle(exchange, content));
        server.start();
    }

    private static void handle(HttpExchange exchange, byte[] content) throws IOException {
        String method = exchange.getRequestMethod();
        Headers resp = exchange.getResponseHeaders();
        resp.add("Accept-Ranges", "bytes");
        resp.add("Content-Length", String.valueOf(content.length));

        if ("HEAD".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }

        if (!"GET".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        String range = exchange.getRequestHeaders().getFirst("Range");
        if (range == null || !range.startsWith("bytes=")) {
            // serve full content (not ideal, but fine)
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(content);
            }
            return;
        }

        long[] parsed = parseRange(range, content.length);
        long start = parsed[0];
        long end = parsed[1];
        int len = (int) (end - start + 1);

        resp.add("Content-Range", "bytes " + start + "-" + end + "/" + content.length);

        exchange.sendResponseHeaders(206, len);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(content, (int) start, len);
        }
    }

    private static long[] parseRange(String rangeHeader, int totalLen) {
        // "bytes=start-end"
        String spec = rangeHeader.substring("bytes=".length()).trim();
        String[] parts = spec.split("-", 2);

        long start = Long.parseLong(parts[0]);
        long end = Long.parseLong(parts[1]);

        if (start < 0 || end < start || end >= totalLen) {
            throw new IllegalArgumentException("Invalid range: " + rangeHeader);
        }
        return new long[]{start, end};
    }

    private static byte[] generateData(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i * 31 + 7);
        }
        return data;
    }

    private static byte[] hash(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return md.digest(data);
    }
}
