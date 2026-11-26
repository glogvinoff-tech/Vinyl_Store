package ru.mirea.vinylstore.error;
public class RateLimitException extends RuntimeException {
    public RateLimitException() { super("Слишком много запросов. Повторите позже"); }
}
