package sf.hw;


public interface CustomRejectedExecutionHandler {
    void rejectedExecution(Runnable r, CustomExecutor e);
}