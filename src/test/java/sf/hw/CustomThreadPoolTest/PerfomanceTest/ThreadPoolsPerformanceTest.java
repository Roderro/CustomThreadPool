package sf.hw.CustomThreadPoolTest.PerfomanceTest;

import org.apache.commons.io.output.TeeOutputStream;
import org.junit.jupiter.api.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.tomcat.util.threads.ThreadPoolExecutor;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import sf.hw.CustomExecutor;
import sf.hw.CustomThreadPool;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Disabled("Benchmark")
@DisplayName("Анализ производительности thread pool'ов: кастомные vs стандартные vs промышленные решения")
public class ThreadPoolsPerformanceTest {

    private static final int WARMUP_ITERATIONS = 2;
    private static final int TEST_ITERATIONS = 4;
    private static final int TASK_COUNT = 10_000;
    private static final int THREAD_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int QUEUE_CAPACITY = 10_000;

    private CustomExecutor customThreadPoolWithSpareThread;
    private CustomExecutor customThreadPoolWithoutSpareThread;
    private java.util.concurrent.ThreadPoolExecutor standardThreadPool;
    private ThreadPoolExecutor tomcatThreadPool;
    private QueuedThreadPool jettyThreadPool;

    private static PrintStream originalOut;
    private static PrintStream printStream;
    private static String outputFilePath;

    @BeforeAll
    static void setUpOutput() throws IOException {
        // Создаем директорию для результатов, если ее нет
        File directory = new File("resultPerformance");
        if (!directory.exists()) {
            directory.mkdir();
        }

        // Генерируем имя файла с датой и временем
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy-HH_mm");
        String timestamp = dateFormat.format(new Date());
        outputFilePath = "resultPerformance/result_" + timestamp + ".txt";

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
    }


    @AfterAll
    static void restoreOutput() {
        System.out.println("Тестирование завершено: " + new Date());
        System.setOut(originalOut);
        System.out.println("Результаты сохранены в: " + outputFilePath);
        printStream.close();
    }

    @BeforeEach
    void setUp() throws Exception {

        //Кастомный пул с "резервными" потоками
        customThreadPoolWithSpareThread = new CustomThreadPool(
                THREAD_COUNT,
                THREAD_COUNT * 2,
                60, TimeUnit.SECONDS,
                QUEUE_CAPACITY / THREAD_COUNT,
                Math.min(Math.max(THREAD_COUNT / 4, 1), 4)
        );

        //Кастомный пул без "резервных" потоков
        customThreadPoolWithoutSpareThread = new CustomThreadPool(
                THREAD_COUNT,
                THREAD_COUNT * 2,
                60, TimeUnit.SECONDS,
                QUEUE_CAPACITY / THREAD_COUNT,
                0
        );


        // Стандартный ThreadPoolExecutor
        standardThreadPool = new java.util.concurrent.ThreadPoolExecutor(
                THREAD_COUNT,
                THREAD_COUNT * 2,
                60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>()
        );

        // Пул потоков Tomcat
        tomcatThreadPool = new ThreadPoolExecutor(
                THREAD_COUNT,
                THREAD_COUNT * 2,
                60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>()
        );

        // Пул потоков Jetty
        jettyThreadPool = new QueuedThreadPool(
                THREAD_COUNT * 2,
                THREAD_COUNT,
                60_000,
                new LinkedBlockingQueue<>()
        );
        jettyThreadPool.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        customThreadPoolWithSpareThread.shutdown();
        customThreadPoolWithoutSpareThread.shutdown();
        standardThreadPool.shutdown();
        tomcatThreadPool.shutdown();
        if (jettyThreadPool != null && !jettyThreadPool.isStopped()) {
            jettyThreadPool.stop();
            jettyThreadPool.join(); // Ожидаем полной остановки
        }
    }

    private long executeTasks(Executor executor, int taskCount, int sleepTime) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(taskCount);
        AtomicInteger completedTasks = new AtomicInteger(0);

        long startTime = System.nanoTime();

        for (int i = 0; i < taskCount; i++) {
            try {
                executor.execute(() -> {
                    try {
                        Thread.sleep(sleepTime);
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


    @Test
    @DisplayName("Сравнение базовой производительности")
    void compareBasicPerformance() throws InterruptedException {
        // Результаты
        System.out.println("\n=== Результаты базового теста производительности ===");

        long customThreadPoolWithSpareThreadTime = testExecutor(customThreadPoolWithSpareThread);
        System.out.printf("CustomThreadPoolWithSpareThread: %d ms%n", customThreadPoolWithSpareThreadTime);

        long customThreadPoolWithoutSpareThreadTime = testExecutor(customThreadPoolWithoutSpareThread);
        System.out.printf("CustomThreadPoolWithoutSpareThreadTime: %d ms%n", customThreadPoolWithoutSpareThreadTime);

        long standardPoolTime = testExecutor(standardThreadPool);
        System.out.printf("ThreadPoolExecutor: %d ms%n", standardPoolTime);

        long tomcatPoolTime = testExecutor(tomcatThreadPool);
        System.out.printf("Tomcat ThreadPool: %d ms%n", tomcatPoolTime);

        long jettyPoolTime = testExecutor(jettyThreadPool);
        System.out.printf("Jetty ThreadPool: %d ms%n", jettyPoolTime);
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


    @Test
    @DisplayName("Тест под высокой нагрузкой")
    void testUnderHighLoad() throws InterruptedException {
        int highLoadTaskCount = TASK_COUNT * 5;

        System.out.println("\n=== Тест под высокой нагрузкой ===");

        long customThreadPoolWithSpareThreadTime = measureHighLoad(customThreadPoolWithSpareThread, highLoadTaskCount);
        System.out.printf("CustomThreadPoolWithSpareThread: %d ms%n", customThreadPoolWithSpareThreadTime);

        long customThreadPoolWithoutSpareThreadTime = measureHighLoad(customThreadPoolWithoutSpareThread, highLoadTaskCount);
        System.out.printf("CustomThreadPoolWithoutSpareThread: %d ms%n", customThreadPoolWithoutSpareThreadTime);

        long standardTime = measureHighLoad(standardThreadPool, highLoadTaskCount);
        System.out.printf("ThreadPoolExecutor: %d ms%n", standardTime);

        long tomcatTime = measureHighLoad(tomcatThreadPool, highLoadTaskCount);
        System.out.printf("Tomcat ThreadPool: %d ms%n", tomcatTime);

        long jettyTime = measureHighLoad(jettyThreadPool, highLoadTaskCount);
        System.out.printf("Jetty ThreadPool: %d ms%n", jettyTime);
    }

    private long measureHighLoad(Executor executor, int taskCount) throws InterruptedException {
        return executeTasks(executor, taskCount, 1);
    }

    @Test
    @DisplayName("Тест с короткоживущими задачами")
    void testShortLivedTasks() throws InterruptedException {
        int shortTaskCount = TASK_COUNT * 2;

        System.out.println("\n=== Тест с короткоживущими задачами ===");

        long customThreadPoolWithSpareThreadTime = measureShortTasks(customThreadPoolWithSpareThread, shortTaskCount);
        System.out.printf("CustomThreadPoolWithSpareThread: %d ms%n", customThreadPoolWithSpareThreadTime);

        long customThreadPoolWithoutSpareThreadTime = measureShortTasks(customThreadPoolWithoutSpareThread
                , shortTaskCount);
        System.out.printf("CustomThreadPoolWithoutSpareThread: %d ms%n", customThreadPoolWithoutSpareThreadTime);

        long standardTime = measureShortTasks(standardThreadPool, shortTaskCount);
        System.out.printf("ThreadPoolExecutor: %d ms%n", standardTime);

        long tomcatTime = measureShortTasks(tomcatThreadPool, shortTaskCount);
        System.out.printf("Tomcat ThreadPool: %d ms%n", tomcatTime);

        long jettyTime = measureShortTasks(jettyThreadPool, shortTaskCount);
        System.out.printf("Jetty ThreadPool: %d ms%n", jettyTime);
    }

    private long measureShortTasks(Executor executor, int taskCount) throws InterruptedException {
        return executeTasks(executor, taskCount, 0);
    }

    @Test
    @DisplayName("Тест с длительными задачами")
    void testLongRunningTasks() throws InterruptedException {
        int longTaskCount = TASK_COUNT / 10;

        System.out.println("\n=== Тест с длительными задачами ===");

        long customThreadPoolWithSpareThreadTime = measureLongTasks(customThreadPoolWithSpareThread, longTaskCount);
        System.out.printf("CustomThreadPoolWithSpareThread: %d ms%n", customThreadPoolWithSpareThreadTime);

        long customThreadPoolWithoutSpareThreadTime = measureLongTasks(customThreadPoolWithoutSpareThread
                , longTaskCount);
        System.out.printf("CustomThreadPoolWithoutSpareThread: %d ms%n", customThreadPoolWithoutSpareThreadTime);

        long standardTime = measureLongTasks(standardThreadPool, longTaskCount);
        System.out.printf("ThreadPoolExecutor: %d ms%n", standardTime);

        long tomcatTime = measureLongTasks(tomcatThreadPool, longTaskCount);
        System.out.printf("Tomcat ThreadPool: %d ms%n", tomcatTime);

        long jettyTime = measureLongTasks(jettyThreadPool, longTaskCount);
        System.out.printf("Jetty ThreadPool: %d ms%n", jettyTime);
    }

    private long measureLongTasks(Executor executor, int taskCount) throws InterruptedException {
        return executeTasks(executor, taskCount, 100);
    }

    @Test
    @DisplayName("Тест использования памяти (результаты приблизительные)")
    void testMemoryUsage() throws InterruptedException {
        System.out.println("\n=== Тест использования памяти ===");

        long customThreadPoolWithSpareThreadTime = measureMemoryUsage(customThreadPoolWithSpareThread);
        System.out.printf("CustomThreadPoolWithSpareThread: %d KB%n", customThreadPoolWithSpareThreadTime);

        long customThreadPoolWithoutSpareThreadTime = measureMemoryUsage(customThreadPoolWithoutSpareThread);
        System.out.printf("CustomThreadPoolWithoutSpareThread: %d KB%n", customThreadPoolWithoutSpareThreadTime);

        long standardMemory = measureMemoryUsage(standardThreadPool);
        System.out.printf("ThreadPoolExecutor memory: %d KB%n", standardMemory);

        long tomcatMemory = measureMemoryUsage(tomcatThreadPool);
        System.out.printf("Tomcat ThreadPool memory: %d KB%n", tomcatMemory);

        long jettyMemory = measureMemoryUsage(jettyThreadPool);
        System.out.printf("Jetty ThreadPool memory: %d KB%n", jettyMemory);
    }

    private long measureMemoryUsage(Executor executor) throws InterruptedException {
        // Прогрев
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            executeTasks(executor, TASK_COUNT, 1);
        }

        long totalMemoryUsed = 0;
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            long before = usedMemory();
            try {
                executeTasks(executor, TASK_COUNT, 1);
            } catch (Exception e) {
                System.err.println("Ошибка при выполнении задач: " + e.getMessage());
            }
            long after = usedMemory();
            totalMemoryUsed += (after - before);
        }

        long averageMemoryUsed = totalMemoryUsed / TEST_ITERATIONS;
        return averageMemoryUsed / 1024;
    }

    private long usedMemory() {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

}
