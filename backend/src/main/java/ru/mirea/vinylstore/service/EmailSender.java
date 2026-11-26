package ru.mirea.vinylstore.service;

public interface EmailSender {
    void send(String from, String to, String subject, String text);
}