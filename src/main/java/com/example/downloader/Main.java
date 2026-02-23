package com.example.downloader;

import java.nio.file.Path;

public class Main {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: <url> <outputFile> [threads] [chunkSizeBytes]");
            System.out.println("Example: https://example.com/file.bin out.bin 4 1048576");
            return;
        }

        String url = args[0];
        Path output = Path.of(args[1]);

        int threads = args.length >= 3 ? Integer.parseInt(args[2]) : 4;
        int chunkSize = args.length >= 4 ? Integer.parseInt(args[3]) : 1_048_576; // 1 MiB

        ParallelDownloader downloader = new ParallelDownloader(threads, chunkSize);
        downloader.download(url, output);

        System.out.println("Downloaded to: " + output.toAbsolutePath());
    }
}