package sf.hw;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class CustomThreadPool implements CustomExecutor {
    private static final Logger logger = LoggerFactory.getLogger(CustomThreadPool.class);
    private final int corePoolSize;
    private final int maxPoolSize;
    private final long keepAliveTime;
    private final TimeUnit timeUnit;
    private final int queueSize;
    private final int minSpareThreads;
    private final CopyOnWriteArrayList<Worker> workers;
    private final ConcurrentLinkedQueue<Runnable> rejectedTasksQueue;
    private final AtomicInteger threadCount = new AtomicInteger(0);
    private final AtomicInteger nextWorkerIndex = new AtomicInteger(0);
    private final AtomicLong countTask = new AtomicLong(0);
    private final AtomicInteger spareThreadCount = new AtomicInteger(0);
    private final ThreadFactory threadFactory;
    private final CustomRejectedExecutionHandler handler;
    private final Lock mainLock = new ReentrantLock();
    private volatile boolean isShutdown = false;


    public CustomThreadPool(int corePoolSize, int maxPoolSize, long keepAliveTime, TimeUnit timeUnit, int queueSize,
                            int minSpareThreads, ThreadFactory threadFactory, CustomRejectedExecutionHandler handler) {
        checkCorePoolMoreMinSpareThreads(corePoolSize, minSpareThreads);
        this.corePoolSize = corePoolSize;
        this.maxPoolSize = maxPoolSize;
        this.keepAliveTime = keepAliveTime;
        this.timeUnit = timeUnit;
        this.queueSize = queueSize;
        this.minSpareThreads = minSpareThreads;
        this.workers = new CopyOnWriteArrayList<>();
        this.threadFactory = threadFactory;
        this.handler = handler;
        this.rejectedTasksQueue = new ConcurrentLinkedQueue<>();
        initializeCoreThreads();
    }


    private void checkCorePoolMoreMinSpareThreads(int corePoolSize, int minSpareThreads) {
        if (corePoolSize < minSpareThreads) {
            throw new IllegalArgumentException("corePoolSize must be greater than minSpareThreads");
        }
    }

    public CustomThreadPool(int corePoolSize, int maxPoolSize, long keepAliveTime, TimeUnit timeUnit, int queueSize, int minSpareThreads) {
        this(corePoolSize, maxPoolSize, keepAliveTime, timeUnit, queueSize, minSpareThreads, new CustomThreadFactory(), new CustomRejectedHandler());
    }

    public int getThreadCount() {
        return threadCount.get();
    }

    public ConcurrentLinkedQueue<Runnable> getRejectedTasksQueue() {
        return rejectedTasksQueue;
    }


    private void initializeCoreThreads() {
        for (int i = 0; i < corePoolSize; i++) {
            addWorker(new LinkedBlockingQueue<>(queueSize));
        }
    }

    private boolean addWorker(LinkedBlockingQueue<Runnable> tasks) {
        if (threadCount.get() < maxPoolSize) {
            Worker worker = new Worker(tasks);
            workers.add(worker);
            worker.thread.start();
            threadCount.incrementAndGet();
            if (tasks.isEmpty()) {
                logger.debug("Add new Worker: {}", worker.thread.getName());
            } else {
                logger.debug("Add new Worker: {} with task № {}",
                        worker.thread.getName(), countTask.incrementAndGet());
            }
            return true;
        }
        return false;
    }

    private boolean addWorkerWithTask(Runnable command) {
        LinkedBlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>(queueSize);
        tasks.add(command);
        return addWorker(tasks);
    }


    private void removeWorker(Worker worker) {
        mainLock.lock();
        try {
            if (threadCount.get() > corePoolSize || !worker.thread.isInterrupted()) {
                workers.remove(worker);
                threadCount.decrementAndGet();
                logger.debug("Delete Worker: {}", worker.thread.getName());
                if (!isShutdown && threadCount.get() < corePoolSize) {
                    while (threadCount.get() < corePoolSize) {
                        addWorker(new LinkedBlockingQueue<>(queueSize));
                    }
                }
            }
        } finally {
            mainLock.unlock();
        }
    }

    public void addTaskInRejectQueue(Runnable command) {
        rejectedTasksQueue.add(command);
        if (rejectedTasksQueue.size() > 1000) {
            logger.warn("Rejected tasks queue size: {} ", rejectedTasksQueue.size());
        }
    }

    @Override
    public void execute(Runnable command) {
        if (isShutdown) {
            throw new RejectedExecutionException("Executor is shutdown");
        }

        // Контролируем минимальное число «резервных» потоков
        if (minSpareThreads != 0) {
            if (spareThreadCount.get() <= minSpareThreads && threadCount.get() < maxPoolSize) {
                addWorkerWithTask(command);
                return;
            }
        }


        // Текущий индекс для Round Robin
        int index = nextWorkerIndex.getAndIncrement() % workers.size();
        int attempts = 0; // Счетчик попыток

        while (attempts < workers.size()) {
            Worker worker = workers.get(index);
            if (worker.isRunning && worker.queue.offer(command)) {
                logger.debug("Task accepted into queue {}: Задача № {}",
                        worker.thread.getName(), countTask.incrementAndGet());
                return;
            }
            index = (index + 1) % workers.size();
            attempts++;
        }

        if (threadCount.get() < maxPoolSize) {
            if (!addWorkerWithTask(command)) {
                handler.rejectedExecution(command, this);
            }
        } else {
            handler.rejectedExecution(command, this);
        }
    }

    @Override
    public <T> Future<T> submit(Callable<T> callable) {
        FutureTask<T> futureTask = new FutureTask<>(callable);
        execute(futureTask);
        return futureTask;
    }

    @Override
    public void shutdown() {
        isShutdown = true;
        logger.debug("Shutdown initiated");
        for (Worker worker : workers) {
            worker.interruptIfIdle();
        }
    }

    @Override
    public void shutdownNow() {
        isShutdown = true;
        logger.debug("ShutdownNow initiated");
        for (Worker worker : workers) {
            worker.interrupt();
        }
    }

    @Override
    public boolean isShutdown() {
        return isShutdown;
    }

    @Override
    public boolean isTerminated() {
        return isShutdown && workers.isEmpty();
    }


    private class Worker implements Runnable {
        private final BlockingQueue<Runnable> queue;
        private final Thread thread;
        private volatile boolean isRunning = true;

        Worker(BlockingQueue<Runnable> queue) {
            this.queue = queue;
            this.thread = threadFactory.newThread(this);
        }

        @Override
        public void run() {
            while (isRunning) {
                try {
                    spareThreadCount.incrementAndGet();
                    if (queue.isEmpty() && !rejectedTasksQueue.isEmpty()) {
                        Runnable rejectedTask = rejectedTasksQueue.poll();
                        if (rejectedTask != null) {
                            logger.debug("Worker {} executes rejectedTask", thread.getName());
                            spareThreadCount.decrementAndGet();
                            rejectedTask.run();
                            spareThreadCount.incrementAndGet();
                        }
                        continue;
                    }
                    Runnable task = queue.poll(keepAliveTime, timeUnit);
                    if (task != null) {
                        logger.debug("Worker {} executes task", thread.getName());
                        spareThreadCount.decrementAndGet();
                        task.run();
                    } else if (threadCount.get() > corePoolSize
                            && spareThreadCount.get() > minSpareThreads || isShutdown) {
                        logger.debug("Worker {} idle timeout, stopping", thread.getName());
                        isRunning = false;
                    }
                } catch (InterruptedException e) {
                    thread.interrupt();
                    spareThreadCount.decrementAndGet();
                    isRunning = false;
                }
            }
            interrupt();
            removeWorker(this);
        }

        void interruptIfIdle() {
            if (queue.isEmpty()) {
                interrupt();
            }
        }

        void interrupt() {
            if (thread.isInterrupted()) {
                return;
            }
            thread.interrupt();
            logger.debug("Worker {} interrupted", thread.getName());
        }
    }


    private static class CustomRejectedHandler implements CustomRejectedExecutionHandler {
        private static final Logger logger = LoggerFactory.getLogger(CustomRejectedHandler.class);

        public CustomRejectedHandler() {
        }

        public void rejectedExecution(Runnable r, CustomExecutor e) {
            if (e instanceof CustomThreadPool customThreadPool) {
                customThreadPool.addTaskInRejectQueue(r);
                logger.debug("Rejected task added to rejectedTaskQueue");
            }
        }
    }
}