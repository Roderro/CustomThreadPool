package sf.hw.CustomThreadPoolTest.CorrectWorkTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sf.hw.CustomExecutor;
import sf.hw.CustomRejectedExecutionHandler;
import sf.hw.CustomThreadFactory;
import sf.hw.CustomThreadPool;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Тестирование CustomThreadPool без «резервных» потоков")
class CustomThreadPoolWithoutSpareTreadsTest extends AbstractCustomThreadPoolTest {

    CustomThreadPoolWithoutSpareTreadsTest() {
        this.COREPOOLSIZE = 4;
        this.MAXPOOLSIZE = 8;
        this.KEEPALIVETIME = 5;
        this.TIMEUNIT = TimeUnit.SECONDS;
        this.QUEUESIZE = 4;
        this.MINSPARETHREADS = 0;
    }

    @Test
    @DisplayName("Тест переполнения очередей потоков")
    void whenQueueFull_thenRejectHandlerCalled() {
        CustomRejectedExecutionHandler handler = mock(CustomRejectedExecutionHandler.class);
        CustomThreadPool pool = new CustomThreadPool(
                1, 1, KEEPALIVETIME, TIMEUNIT, 1, 0,
                new CustomThreadFactory(), handler
        );

        // Заполняем очередь
        pool.execute(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
            }
        });
        pool.execute(() -> {
        });

        // Пытаемся добавить третью задачу
        Runnable rejectedTask = () -> {
        };
        pool.execute(rejectedTask);

        verify(handler, timeout(100)).rejectedExecution(rejectedTask, pool);
        pool.shutdownNow();
    }

    @Test
    @DisplayName("Тестирование динамического расширения пула")
    void whenCoreThreadsBusy_thenNewThreadCreated() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        // Блокируем основные потоки
        blockAndFillCorePool(latch);

        // Добавляем дополнительную задачу
        threadPool.execute(() -> {
        });

        // Проверяем, что создался новый поток
        assertEquals(COREPOOLSIZE + 1, threadPool.getThreadCount());
        latch.countDown();
    }

    @Test
    @DisplayName("Тестирование удаления освободившихся потоков")
    void whenThreadIdle_thenItIsTerminatedAfterKeepAlive() throws InterruptedException {
        // Создаем пул с коротким временем жизни
        CustomExecutor pool = new CustomThreadPool(
                1, 2, 50, TimeUnit.MILLISECONDS, 1, 0
        );
        // Блокируем core thread
        CountDownLatch latch = new CountDownLatch(1);
        pool.execute(() -> {
            try {
                latch.await();
            } catch (InterruptedException ignored) {
            }
        });

        // Добавляем задачу, которая создаст дополнительный поток
        pool.execute(() -> {
        });

        // Ждем, пока дополнительный поток завершится по таймауту
        Thread.sleep(100);
        assertEquals(1, pool.getThreadCount());
        latch.countDown();
    }

}

