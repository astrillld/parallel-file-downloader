#Parallel File Downloader

A multithreaded HTTP file downloader implemented in Java.  
It downloads file chunks in parallel using HTTP Range requests and merges them into a single output file.

#How it works
1. Sends a HEAD request to detect Content-Length and verify Accept-Ranges: bytes.
2. Splits the file into byte ranges (chunks).
3. Downloads chunks concurrently using a fixed thread pool.
4. Writes each chunk into its position using RandomAccessFile.

#Build & Test
bash
./gradlew clean test

#Optional: run a local server via Docker 