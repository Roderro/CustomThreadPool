package sf.hw.CustomThreadPoolTest.PerfomanceTest;


import org.apache.commons.io.output.TeeOutputStream;
import org.junit.jupiter.api.*;
import sf.hw.CustomThreadPool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;


@Disabled("Benchmark")
@DisplayName("Анализ производительности thread pool'ов при разных конфигурациях")
public class CustomThreadPoolStatePerformanceTest {
    private static final int WARMUP_ITERATIONS = 2;
    private static final int TEST_ITERATIONS = 4;
    private static final int TASK_COUNT = 10_000;
    private static final int THREAD_COUNT = Runtime.getRuntime().availableProcessors();
    private static int countState = 0;

    private static PrintStream originalOut;
    private static PrintStream printStream;
    private static String outputFilePath;

    private CustomThreadPool threadPool;
    private int COREPOOLSIZE;
    private int MAXPOOLSIZE;
    private long KEEPALIVETIME;
    private TimeUnit TIMEUNIT = TimeUnit.SECONDS;
    private int QUEUESIZE;
    private int MINSPARETHREADS;

    @BeforeAll
    static void setUpOutput() throws IOException {
        // Создаем директорию для результатов, если ее нет
        File directory = new File("resultStatePerformance");
        if (!directory.exists()) {
            directory.mkdir();
        }

        // Генерируем имя файла с датой и временем
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy-HH_mm");
        String timestamp = dateFormat.format(new Date());
        outputFilePath = "resultStatePerformance/result_" + timestamp + ".txt";

        // Сохраняем оригинальный System.out
        originalOut = System.out;

        FileOutputStream fileOutputStream = new FileOutputStream(outputFilePath);
        TeeOutputStream teeOutputStream = new TeeOutputStream(System.out, fileOutputStream);
        printStream = new PrintStream(teeOutputStream, true);
        System.setOut(printStream);

        System.out.println("Тестирование начато: " + new Date());
        System.out.println("Параметры системы:");
        System.out.println("Доступные ядра процессора: " + THREAD_COUNT);
        System.out.println("Количество задач: " + TASK_COUNT);
        System.out.println("Итерации прогрева: " + WARMUP_ITERATIONS);
        System.out.println("Тестовые итерации: " + TEST_ITERATIONS);
        printTableHeader();
    }

    @AfterAll
    static void restoreOutput() {
        System.out.println("+------+--------------+-----------------+----------------+------------+-----------------+--------------+");
        System.out.println("Тестирование завершено: " + new Date());
        System.setOut(originalOut);
        System.out.println("Результаты сохранены в: " + outputFilePath);
        printStream.close();
    }

    private static void printTableHeader() {
        System.out.println("+------+--------------+-----------------+----------------+------------+-----------------+--------------+");
        System.out.println("| №    | CorePoolSize | MaximumPoolSize | KeepAliveTime  | QueueSize  | MinSpareThreads | Время (ms)   |");
        System.out.println("+------+--------------+-----------------+----------------+------------+-----------------+--------------+");
    }


    private long executeTasksWithRandomWorkTimeTask(Executor executor, int taskCount, Random random) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(taskCount);
        AtomicInteger completedTasks = new AtomicInteger(0);

        long startTime = System.nanoTime();
        for (int i = 0; i < taskCount; i++) {
            try {
                int finalSleepTime = random.nextInt(10);
                executor.execute(() -> {
                    try {
                        Thread.sleep(finalSleepTime);
                        completedTasks.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        latch.countDown();
                    }
                });
            } catch (RejectedExecutionException e) {
                latch.countDown();
            }
        }

        latch.await();
        long endTime = System.nanoTime();
        assertEquals(taskCount, completedTasks.get());

        return TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
    }

    private long testExecutor(Executor executor) throws InterruptedException {
        Random random = new Random();
        // Прогрев
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            executeTasksWithRandomWorkTimeTask(executor, TASK_COUNT, random);
        }
        // Основной тест
        long totalTime = 0;
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            totalTime += executeTasksWithRandomWorkTimeTask(executor, TASK_COUNT, random);
        }
        return totalTime / TEST_ITERATIONS;
    }

    private void setState(int corePollSize, int maxPollSize, long keepAliveTime, TimeUnit timeUnit, int queueSize, int minSpareThreads) {
        COREPOOLSIZE = corePollSize;
        MAXPOOLSIZE = maxPollSize;
        KEEPALIVETIME = keepAliveTime;
        TIMEUNIT = timeUnit;
        QUEUESIZE = queueSize;
        MINSPARETHREADS = minSpareThreads;
    }

    private void printStateResult(long resultTime) {
        System.out.printf("| %-4d | %-12d | %-15d | %-14d | %-10d | %-15d | %-12d |%n",
                countState++, COREPOOLSIZE, MAXPOOLSIZE, KEEPALIVETIME, QUEUESIZE, MINSPARETHREADS, resultTime);
    }

    private CustomThreadPool createStatePool() {
        return new CustomThreadPool(COREPOOLSIZE, MAXPOOLSIZE, KEEPALIVETIME, TIMEUNIT, QUEUESIZE, MINSPARETHREADS);
    }

    @Test
    @Order(1)
    @DisplayName("Конфигурация 1 - THREAD_COUNT потоков, max THREAD_COUNT*2, таймаут 1с, очередь 100, резервных 1")
    void state1Performance() throws InterruptedException {
        setState(THREAD_COUNT, THREAD_COUNT * 2, 1000, TimeUnit.MILLISECONDS, 100, 1);
        threadPool = createStatePool();
        long stateThreadPoolTime = testExecutor(threadPool);
        printStateResult(stateThreadPoolTime);
    }

    @Test
    @Order(2)
    @DisplayName("Конфигурация 2 -  8 потоков, max 16, таймаут 1с, очередь 100, резервных 1")
    void state2Performance() throws InterruptedException {
        setState(8, 16, 1000, TimeUnit.MILLISECONDS, 100, 1);
        threadPool = createStatePool();
        long stateThreadPoolTime = testExecutor(threadPool);
        printStateResult(stateThreadPoolTime);
    }


    @Test
    @Order(3)
    @DisplayName("Конфигурация 3 - 4 потока, max 16, таймаут 1с, очередь 100, резервных 1 ")
    void state3Performance() throws InterruptedException {
        setState(4, 8, 1000, TimeUnit.MILLISECONDS, 100, 1);
        threadPool = createStatePool();
        long stateThreadPoolTime = testExecutor(threadPool);
        printStateResult(stateThreadPoolTime);
    }

    @Test
    @Order(4)
    @DisplayName("Конфигурация 4 - 2 потока, max 4, таймаут 1с, очередь 100, резервных 1")
    void state4Performance() throws InterruptedException {
        setState(2, 4, 1000, TimeUnit.MILLISECONDS, 100, 1);
        threadPool = createStatePool();
        long stateThreadPoolTime = testExecutor(threadPool);
        printStateResult(stateThreadPoolTime);
    }

    @Test
    @Order(5)
    @DisplayName("Конфигурация 5 - THREAD_COUNT потоков, max THREAD_COUNT*2, таймаут 10 мс, очередь 100, резервных 1")
    void state5Performance() throws InterruptedException {
        setState(THREAD_COUNT, THREAD_COUNT * 2, 10, TimeUnit.MILLISECONDS, 100, 1);
        threadPool = createStatePool();
        long stateThreadPoolTime = testExecutor(threadPool);
        printStateResult(stateThreadPoolTime);
    }

    @Test
    @Order(6)
    @DisplayName("Конфигурация 6 - THREAD_COUNT потоков, max THREAD_COUNT*2, таймаут 1 с, очередь 100, резервных 2")
    void state6Performance() throws InterruptedException {
        setState(THREAD_COUNT, THREAD_COUNT * 2, 1000, TimeUnit.MILLISECONDS, 100, 2);
        threadPool = createStatePool();
        long stateThreadPoolTime = testExecutor(threadPool);
        printStateResult(stateThreadPoolTime);
    }

    @Test
    @Order(7)
    @DisplayName("Конфигурация 7 - THREAD_COUNT потоков, max THREAD_COUNT*2, таймаут 1 с, очередь 1000, резервных 2")
    void state7Performance() throws InterruptedException {
        setState(THREAD_COUNT, THREAD_COUNT * 2, 1000, TimeUnit.MILLISECONDS, 1000, 2);
        threadPool = createStatePool();
        long stateThreadPoolTime = testExecutor(threadPool);
        printStateResult(stateThreadPoolTime);
    }

    @Test
    @Order(8)
    @DisplayName("Конфигурация 8 - THREAD_COUNT потоков, max THREAD_COUNT*2, таймаут 1 с, очередь 100, резервных 4")
    void state8Performance() throws InterruptedException {
        setState(THREAD_COUNT, THREAD_COUNT * 2, 1000, TimeUnit.MILLISECONDS, 100, 4);
        threadPool = createStatePool();
        long stateThreadPoolTime = testExecutor(threadPool);
        printStateResult(stateThreadPoolTime);
    }

    @Test
    @Order(9)
    @DisplayName("Конфигурация 9 - THREAD_COUNT потоков, max THREAD_COUNT*2, таймаут 1 с, очередь 1000, резервных 4")
    void state9Performance() throws InterruptedException {
        setState(THREAD_COUNT, THREAD_COUNT * 2, 1000, TimeUnit.MILLISECONDS, 1000, 4);
        threadPool = createStatePool();
        long stateThreadPoolTime = testExecutor(threadPool);
        printStateResult(stateThreadPoolTime);
    }

    @Test
    @Order(10)
    @DisplayName("Конфигурация 10 (минимальная) - 1 потоков, max 2, таймаут 500 мс, очередь 10, резервных 0")
    void state10Performance() throws InterruptedException {
        setState(1, 2, 500, TimeUnit.MILLISECONDS, 10, 0);
        threadPool = createStatePool();
        long stateThreadPoolTime = testExecutor(threadPool);
        printStateResult(stateThreadPoolTime);
    }

    @Test
    @Order(11)
    @DisplayName("Конфигурация 11 (большая очередь) - THREAD_COUNT потоков, max THREAD_COUNT * 2, таймаут 1 с, очередь 5000, резервных 2")
    void state11Performance() throws InterruptedException {
        setState(THREAD_COUNT, THREAD_COUNT * 2, 1000, TimeUnit.MILLISECONDS, 5000, 2);
        threadPool = createStatePool();
        long stateThreadPoolTime = testExecutor(threadPool);
        printStateResult(stateThreadPoolTime);
    }

    @Test
    @Order(12)
    @DisplayName("Конфигурация 11 (агрессивное масштабирование) - 2 потоков, max 32, таймаут 100 мс, очередь 50, резервных 1")
    void state12Performance() throws InterruptedException {
        setState(2, 32, 100, TimeUnit.MILLISECONDS, 50, 1);
        threadPool = createStatePool();
        long stateThreadPoolTime = testExecutor(threadPool);
        printStateResult(stateThreadPoolTime);
    }

    @Test
    @Order(13)
    @DisplayName("Конфигурация 13 (консервативная настройка) - THREAD_COUNT / 2 потоков, max THREAD_COUNT, таймаут 2 с, очередь 200, резервных 1")
    void state13Performance() throws InterruptedException {
        setState(THREAD_COUNT / 2, THREAD_COUNT, 2000, TimeUnit.MILLISECONDS, 200, 1);
        threadPool = createStatePool();
        long stateThreadPoolTime = testExecutor(threadPool);
        printStateResult(stateThreadPoolTime);
    }

    @Test
    @Order(14)
    @DisplayName("Конфигурация 14 (без резервных потоков) -  THREAD_COUNT потоков, max THREAD_COUNT * 2, таймаут 1 с, очередь 100, резервных 0")
    void state14Performance() throws InterruptedException {
        setState(THREAD_COUNT, THREAD_COUNT * 2, 1000, TimeUnit.MILLISECONDS, 100, 0);
        threadPool = createStatePool();
        long stateThreadPoolTime = testExecutor(threadPool);
        printStateResult(stateThreadPoolTime);
    }

    @Test
    @Order(15)
    @DisplayName("Конфигурация 15 (короткое время жизни потоков) - 4 потоков, max 16, таймаут 10 мс, очередь 100, резервных 2")
    void state15Performance() throws InterruptedException {
        setState(4, 16, 10, TimeUnit.MILLISECONDS, 100, 2);
        threadPool = createStatePool();
        long stateThreadPoolTime = testExecutor(threadPool);
        printStateResult(stateThreadPoolTime);
    }

    @Test
    @Order(16)
    @DisplayName("Конфигурация 16 (баланс между ядрами и потоками) - THREAD_COUNT потоков, max THREAD_COUNT * 4, таймаут 500 мс, очередь 200, резервных THREAD_COUNT / 2")
    void state16Performance() throws InterruptedException {
        setState(THREAD_COUNT, THREAD_COUNT * 4, 500, TimeUnit.MILLISECONDS, 200, THREAD_COUNT / 2);
        threadPool = createStatePool();
        long stateThreadPoolTime = testExecutor(threadPool);
        printStateResult(stateThreadPoolTime);
    }

    @Test
    @Order(17)
    @DisplayName("Конфигурация 17 (максимальные резервные потоки) - THREAD_COUNT потоков, max THREAD_COUNT * 2, таймаут 1 с, очередь 100, резервных THREAD_COUNT")
    void state17Performance() throws InterruptedException {
        setState(THREAD_COUNT, THREAD_COUNT * 2, 1000, TimeUnit.MILLISECONDS, 100, THREAD_COUNT);
        threadPool = createStatePool();
        long stateThreadPoolTime = testExecutor(threadPool);
        printStateResult(stateThreadPoolTime);
    }

    @Test
    @Order(18)
    @DisplayName("Конфигурация 18 (экстремально большая очередь) - 2 потоков, max 8, таймаут 1 с, очередь 10000, резервных 1")
    void state18Performance() throws InterruptedException {
        setState(2, 8, 1000, TimeUnit.MILLISECONDS, 10000, 1);
        threadPool = createStatePool();
        long stateThreadPoolTime = testExecutor(threadPool);
        printStateResult(stateThreadPoolTime);
    }

    @Test
    @Order(19)
    @DisplayName("Конфигурация 19 (равные core и max потоки) - THREAD_COUNT потоков, max THREAD_COUNT, таймаут 1 с, очередь 100, резервных 1")
    void state19Performance() throws InterruptedException {
        setState(THREAD_COUNT, THREAD_COUNT, 1000, TimeUnit.MILLISECONDS, 100, 1);
        threadPool = createStatePool();
        long stateThreadPoolTime = testExecutor(threadPool);
        printStateResult(stateThreadPoolTime);
    }

    @Test
    @Order(20)
    @DisplayName("Конфигурация 20 (оптимальная по предыдущим тестам) - THREAD_COUNT потоков, max THREAD_COUNT * 2, таймаут 500 мс, очередь 200, резервных 2")
    void state20Performance() throws InterruptedException {
        // Конфигурация, показавшая лучшие результаты в предыдущих тестах
        setState(THREAD_COUNT, THREAD_COUNT * 2, 500, TimeUnit.MILLISECONDS, 200, 2);
        threadPool = createStatePool();
        long stateThreadPoolTime = testExecutor(threadPool);
        printStateResult(stateThreadPoolTime);
    }
}
