# Parallel File Downloader
A multithreaded HTTP file downloader implemented in Java.

## The downloader:
 1. Sends a `HEAD` request to retrieve `Content-Length`
 2. Verifies `Accept-Ranges: bytes`
 3. Splits the file into byte ranges (chunks)
 4. Writes each chunk directly to its offset in the output file using a shared `FileChannel`
 5. If any chunk fails, all remaining downloads are cancelled immediately 

## What changed:
**`FileChannel` instead of `RandomAccessFile` per chunk**
   A single `FileChannel` is opened for the entire download. Each thread writes to its own non-overlapping byte range using `channel.write(buffer, position)`, which avoids the overhead of repeatedly opening and closing the file.
**`ProgressListener` callback**
   An optional `ProgressListener` can be passed to the constructor to track download progress.

## Usage
```bash
./gradlew run --args="<url> <outputFile> [threads] [chunkSizeBytes]"
```
Start a local test server with Docker:
```bash
docker run --rm -p 8080:80 -v /path/to/your/directory:/usr/local/apache2/htdocs/ httpd:latest
```
## Run tests

```bash
./gradlew clean test
```
