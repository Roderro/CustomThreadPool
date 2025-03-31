package sf.hw.CustomThreadPoolTest.CorrectWorkTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sf.hw.CustomThreadPool;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

abstract class AbstractCustomThreadPoolTest {
    CustomThreadPool threadPool;
    int COREPOOLSIZE;
    int MAXPOOLSIZE;
    long KEEPALIVETIME;
    TimeUnit TIMEUNIT = TimeUnit.SECONDS;
    int QUEUESIZE;
    int MINSPARETHREADS;

    @BeforeEach
    void setUp() {
        threadPool = new CustomThreadPool(
                COREPOOLSIZE,
                MAXPOOLSIZE,
                KEEPALIVETIME,
                TIMEUNIT,
                QUEUESIZE,
                MINSPARETHREADS
        );
    }

    @AfterEach
    void tearDown() {
        if (!threadPool.isShutdown()) {
            threadPool.shutdownNow();
        }
    }

    void blockAndFillSomePartPool(CountDownLatch latch, int countBlocks) throws InterruptedException {
        for (int i = 0; i < countBlocks; i++) {
            threadPool.execute(() -> {
                try {
                    latch.await();
                } catch (InterruptedException e) {
                }
            });
            Thread.sleep(5);
        }
    }

    void blockAndFillCorePool(CountDownLatch latch) throws InterruptedException {
        // Блокируем основные потоки
        blockAndFillSomePartPool(latch, COREPOOLSIZE * (QUEUESIZE + 1));
    }

    void blockAndFillPool(CountDownLatch latch) throws InterruptedException {
        // Блокируем и заполняем весь пул
        blockAndFillSomePartPool(latch, MAXPOOLSIZE * (QUEUESIZE + 1));
    }


    @Test
    @DisplayName("Тест инициализации пула")
    void whenCreated_thenCoreThreadsInitialized() {
        assertEquals(COREPOOLSIZE, threadPool.getThreadCount());
        assertFalse(threadPool.isShutdown());
        assertFalse(threadPool.isTerminated());
    }

    @Test
    @DisplayName("Тест выполнения задачи")
    void whenExecuteTask_thenTaskIsExecuted() throws InterruptedException {
        AtomicInteger counter = new AtomicInteger(0);
        Runnable task = counter::incrementAndGet;

        threadPool.execute(task);
        Thread.sleep(50);

        assertEquals(1, counter.get());
    }

    @Test
    @DisplayName("Тестирование добавления задачи после shutdown")
    void whenShutdown_thenNewTasksRejected() {
        threadPool.shutdown();
        assertThrows(RejectedExecutionException.class,
                () -> threadPool.execute(() -> {
                }));
    }

    @Test
    @DisplayName("Тестирование прерывания задач при shutdownNow")
    void whenShutdownNow_thenRunningTasksInterrupted() throws InterruptedException {
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch taskInterrupted = new CountDownLatch(1);

        threadPool.execute(() -> {
            taskStarted.countDown();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                taskInterrupted.countDown();
            }
        });
        taskStarted.await();
        threadPool.shutdownNow();
        assertTrue(taskInterrupted.await(100, TimeUnit.MILLISECONDS));
    }


    @Test
    @DisplayName("Тестирования работы с Callable и Future")
    void whenSubmitCallable_thenFutureReturnsResult() throws Exception {
        Future<String> future = threadPool.submit(() -> "test");
        assertEquals("test", future.get());
    }

    @Test
    @DisplayName("Тестирование обработки отклоненных задач")
    void whenTaskRejected_thenAddedToRejectedQueue() throws InterruptedException, ExecutionException {
        CountDownLatch latch = new CountDownLatch(1);
        // Блокируем потоки
        blockAndFillPool(latch);

        // Добавляем отклоненную задачу
        Callable<Boolean> rejectedTask = () -> {
            return true;
        };
        Future<Boolean> rejectedResponse = threadPool.submit(rejectedTask);
        // Проверяем что задача попала в очередь отклоненных задач
        assertEquals(1, threadPool.getRejectedTasksQueue().size());
        // Освобождаем поток и проверяем, что отклоненная задача была выполнена
        latch.countDown();
        Thread.sleep(100);
        assertTrue(rejectedResponse.get());
    }

    @Test
    @DisplayName("Тестирование обработки отклоненных задач,после shutdown")
    void whenTaskRejected_thenAddedToRejectedQueueAndCompleteAfterShutdown() throws InterruptedException, ExecutionException {
        CountDownLatch latch = new CountDownLatch(1);
        // Блокируем потоки
        blockAndFillPool(latch);

        // Добавляем отклоненную задачу
        Callable<Boolean> rejectedTask = () -> {
            return true;
        };
        Future<Boolean> rejectedResponse = threadPool.submit(rejectedTask);
        // Проверяем что задача попала в очередь отклоненных задач
        assertEquals(1, threadPool.getRejectedTasksQueue().size());
        threadPool.shutdown();
        // Освобождаем поток и проверяем, что отклоненная задача была выполнена после shutdown
        latch.countDown();
        Thread.sleep(100);
        assertTrue(rejectedResponse.get());
    }

    @Test
    @DisplayName("Тестирования работы с Callable и Future при возникновение ошибки")
    void whenSubmitFailingCallable_thenFutureThrowsException() {
        Future<?> future = threadPool.submit(() -> {
            throw new RuntimeException("error");
        });
        assertThrows(ExecutionException.class, future::get);
    }

    @Test
    @DisplayName("Тестирование нагрузки")
    void underHighLoad_thenAllTasksCompleted() throws InterruptedException {
        int taskCount = 10000;
        testThreadPool(threadPool, taskCount);
    }

    private void testThreadPool(Executor executor, int taskCount) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(taskCount);
        AtomicInteger completedTasks = new AtomicInteger(0);

        for (int i = 0; i < taskCount; i++) {
            executor.execute(() -> {
                try {
                    Thread.sleep(1);
                    completedTasks.incrementAndGet();
                    latch.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertEquals(taskCount, completedTasks.get());
    }

}
