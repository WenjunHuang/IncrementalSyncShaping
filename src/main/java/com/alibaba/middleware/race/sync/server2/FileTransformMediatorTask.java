package com.alibaba.middleware.race.sync.server2;

import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.alibaba.middleware.race.sync.Constants.LINE_SPLITTER;
import static com.alibaba.middleware.race.sync.server2.FileUtil.unmap;
import static com.alibaba.middleware.race.sync.server2.PipelinedComputation.WORK_NUM;
import static com.alibaba.middleware.race.sync.server2.PipelinedComputation.fileTransformPool;

/**
 * Created by yche on 6/16/17.
 * used by the master thread
 */
public class FileTransformMediatorTask {
    private Queue<Future<?>> prevFutureQueue = new LinkedList<>();
    private MappedByteBuffer mappedByteBuffer;
    private int currChunkLength;
    boolean isFinished = false;

    public FileTransformMediatorTask() {
        isFinished = true;
    }

    public FileTransformMediatorTask(MappedByteBuffer mappedByteBuffer, int currChunkLength) {
        this.mappedByteBuffer = mappedByteBuffer;
        this.currChunkLength = currChunkLength;
    }

    private static Future<?> prevFuture = new Future<Object>() {
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public Object get() throws InterruptedException, ExecutionException {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return null;
        }
    };

    private static ByteBuffer prevRemainingBytes = ByteBuffer.allocate(32 * 1024);

    // previous tail, should be copied into task
    private int preparePrevBytes() {
        int end = 0;

        if (prevRemainingBytes.position() > 0 && prevRemainingBytes.get(prevRemainingBytes.position() - 1) != LINE_SPLITTER) {
            byte myByte;
            // stop at `\n`
            while ((myByte = mappedByteBuffer.get(end)) != LINE_SPLITTER) {
                prevRemainingBytes.put(myByte);
                end++;
            }
            prevRemainingBytes.put(myByte);
            end++;
        }
        prevRemainingBytes.flip();
        return end;
    }

    private int computeEnd(int smallChunkLastIndex) {
        int end = smallChunkLastIndex;
        while (mappedByteBuffer.get(end) != LINE_SPLITTER) {
            end--;
        }
        end += 1;
        return end;
    }

    // 2nd work: mergeAnother remaining, compute [start, end)
    private void assignTransformTasks() {
        int avgTask = currChunkLength / WORK_NUM;

        // index pair
        int start;
        int end = preparePrevBytes();

        // 1st: first worker
        start = end;
        end = computeEnd(avgTask - 1);
        FileTransformTask fileTransformTask;
        if (prevRemainingBytes.limit() > 0) {
            ByteBuffer tmp = ByteBuffer.allocate(prevRemainingBytes.limit());
            tmp.put(prevRemainingBytes);
            fileTransformTask = new FileTransformTask(mappedByteBuffer, start, end, tmp, prevFuture);
        } else {
            fileTransformTask = new FileTransformTask(mappedByteBuffer, start, end, prevFuture);
        }

        prevFuture = fileTransformPool.submit(fileTransformTask);
        prevFutureQueue.add(prevFuture);

        // 2nd: subsequent workers
        for (int i = 1; i < WORK_NUM; i++) {
            start = end;
            int smallChunkLastIndex = i < WORK_NUM - 1 ? avgTask * (i + 1) - 1 : currChunkLength - 1;
            end = computeEnd(smallChunkLastIndex);
            fileTransformTask = new FileTransformTask(mappedByteBuffer, start, end, prevFuture);
            prevFuture = fileTransformPool.submit(fileTransformTask);
            prevFutureQueue.add(prevFuture);
        }


        // current tail, reuse and then put
        prevRemainingBytes.clear();
        for (int i = end; i < currChunkLength; i++) {
            prevRemainingBytes.put(mappedByteBuffer.get(i));
        }
    }


    private void oneChunkComputation() {
        assignTransformTasks();
        while (!prevFutureQueue.isEmpty()) {
            try {
                prevFutureQueue.poll().get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }
    }

    private void finish() {
        unmap(mappedByteBuffer);
    }

    public void transform() {
        oneChunkComputation();

        // close stream, and unmap
        finish();
    }
}