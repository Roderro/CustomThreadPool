package sf.hw.CustomThreadPoolTest.CorrectWorkTest;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sf.hw.CustomExecutor;
import sf.hw.CustomThreadPool;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Тестирование CustomThreadPool c «резервными» потоками")
class CustomThreadPoolWithSpareThreadsTest extends AbstractCustomThreadPoolTest {

    CustomThreadPoolWithSpareThreadsTest() {
        this.COREPOOLSIZE = 4;
        this.MAXPOOLSIZE = 8;
        this.KEEPALIVETIME = 5;
        this.TIMEUNIT = TimeUnit.SECONDS;
        this.QUEUESIZE = 4;
        this.MINSPARETHREADS = 2;
    }

    @Test
    @DisplayName("Проверка не возможности создать пул, где corePollSize < minSpareThread")
    void whenCorePoolSizeLessThanMinSpareThread() throws InterruptedException {
        // Создаем пул , где corePollSize < minSpareThread"
        assertThrows(IllegalArgumentException.class, () ->
                new CustomThreadPool(1, 3, 50, TimeUnit.MILLISECONDS, 10, 2)
        );
    }


    @Test
    @DisplayName("Тестирование динамического поддержания минимального числа «резервных» потоков")
    void whenMinSpareThreadsBusy_thenNewThreadCreated() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        int countBlockThreads = COREPOOLSIZE - MINSPARETHREADS + 2;
        // Блокируем потоки для создания резервных
        blockAndFillSomePartPool(latch, countBlockThreads);
        int correctCountThread = Math.min(countBlockThreads + MINSPARETHREADS, MAXPOOLSIZE);
        // Проверяем, что создались резервные потоки
        assertEquals(correctCountThread, threadPool.getThreadCount());
        latch.countDown();
    }

    @Test
    @DisplayName("Тестирование удаления освободившихся потоков с учетом поддержания минимального числа «резервных» потоков")
    void whenThreadIdle_thenItIsTerminatedAfterKeepAlive() throws InterruptedException {
        // Создаем пул с коротким временем жизни
        CustomExecutor pool = new CustomThreadPool(
                1, 3, 50, TimeUnit.MILLISECONDS, 10, 1
        );

        CountDownLatch latch = new CountDownLatch(1);
        // Добавляем задачу, которая создаст резервный поток
        pool.execute(() -> {
            try {
                latch.await();
            } catch (InterruptedException ignored) {
            }
        });

        Thread.sleep(100);
        // Резервный поток должен остаться
        assertEquals(2, pool.getThreadCount());
        latch.countDown();
        Thread.sleep(100);
        // Резервный поток должен удалиться
        assertEquals(1, pool.getThreadCount());
    }

}