/*
 *  Copyright 2023 The original authors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package dev.morling.onebrc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.TreeMap;
import java.util.concurrent.*;

public class CalculateAverage_maarlf {

    private static final String FILE = "./measurements.txt";
    private static final int CHUNK_SIZE = 1024 * 1024;
    private static final int THREAD_POOL_SIZE = Runtime.getRuntime()
            .availableProcessors();

    private static record ResultRow(
        double min,
        double max,
        double sum,
        int count
    ) {
        public ResultRow(double measurement) {
            this(measurement, measurement, measurement, 1);
        }

        public ResultRow merge(ResultRow other) {
            double newMin = Math.min(this.min, other.min);
            double newMax = Math.max(this.max, other.max);
            double newSum = this.sum + other.sum;
            int newCount = this.count + other.count;
            return new ResultRow(newMin, newMax, newSum, newCount);
        }

        public double mean() {
            return round(sum) / count;
        }

        private double round(double value) {
            return Math.round(value * 10.0) / 10.0;
        }

        public String toString() {
            return min + "/" + round(mean()) + "/" + max;
        }
    }

    public static void main(String[] args) {
        long fileSize;
        ConcurrentHashMap<String, ResultRow> results = new ConcurrentHashMap<>();

        try (
                FileChannel channel = FileChannel.open(
                        Paths.get(FILE),
                        StandardOpenOption.READ)) {
            fileSize = channel.size();
            long position = 0;
            ExecutorService executor = Executors.newFixedThreadPool(
                    THREAD_POOL_SIZE);

            while (position < fileSize) {
                long size = Math.min(CHUNK_SIZE, fileSize - position);
                ByteBuffer buffer = channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        position,
                        size);

                int bufferSize = buffer.limit();
                while (buffer.get(bufferSize - 1) != '\n') {
                    bufferSize--;
                }
                buffer.limit(bufferSize);
                position += bufferSize;

                executor.submit(() -> processBuffer(buffer, results));
            }

            executor.shutdown();
            executor.awaitTermination(1, TimeUnit.HOURS);
        }
        catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }

        System.out.println(new TreeMap<>(results));
    }

    private static void processBuffer(
                                      ByteBuffer buffer,
                                      ConcurrentHashMap<String, ResultRow> results) {
        byte[] lineBuffer = new byte[128];
        int index = 0;

        while (buffer.hasRemaining()) {
            byte c = buffer.get();
            if (c != '\n') {
                lineBuffer[index] = c;
                index++;
            }
            else {
                String line = new String(
                        lineBuffer,
                        0,
                        index,
                        StandardCharsets.UTF_8);
                processLine(line, results);
                index = 0;
            }
        }
    }

    private static void processLine(
                                    String line,
                                    ConcurrentHashMap<String, ResultRow> results) {
        int delimiterIndex = line.indexOf(';');
        String station = line.substring(0, delimiterIndex);
        double measurement = Double.parseDouble(
                line.substring(delimiterIndex + 1));

        results.merge(station, new ResultRow(measurement), (oldVal, newVal) -> oldVal.merge(newVal));
    }
}
