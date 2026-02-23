Parallel File Downloader
A multithreaded HTTP file downloader implemented in Java.

The downloader:
 Sends a HEAD request to retrieve Content-Length
 Verifies Accept-Ranges: bytes
 Splits the file into byte ranges (chunks)
 Downloads chunks in parallel using HTTP Range requests
 Merges them into a single output file using RandomAccessFile

Build & Test
Run unit tests:
bash
./gradlew clean test