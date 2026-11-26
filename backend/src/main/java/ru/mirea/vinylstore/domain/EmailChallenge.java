package ru.mirea.vinylstore.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** Единственный актуальный код для адреса; исходный код никогда не сохраняется. */
@Entity
@Table(name="email_challenge")
public class EmailChallenge {
    @Id @Column(length=254) public String email;
    @Column(nullable=false,length=20) public String purpose;
    @Column(name="code_hash",nullable=false,length=100) public String codeHash;
    @Column(name="password_hash",length=100) public String passwordHash;
    @Column(name="display_name",length=160) public String displayName;
    @Column(name="expires_at",nullable=false) public Instant expiresAt;
    @Column(name="sent_at",nullable=false) public Instant sentAt;
    @Column(name="window_start",nullable=false) public Instant windowStart;
    @Column(name="send_count",nullable=false) public int sendCount;
    @Column(nullable=false) public int attempts;
    @Column(nullable=false) public boolean consumed;
    protected EmailChallenge() {}
    public EmailChallenge(String email) { this.email=email; }
}
