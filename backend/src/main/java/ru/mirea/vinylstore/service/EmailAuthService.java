package ru.mirea.vinylstore.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mirea.vinylstore.domain.*;
import ru.mirea.vinylstore.dto.EmailAuthDtos.*;
import ru.mirea.vinylstore.dto.AuthDtos.AuthResponse;
import ru.mirea.vinylstore.error.*;
import ru.mirea.vinylstore.repository.*;
import ru.mirea.vinylstore.security.PasswordPolicy;
import ru.mirea.vinylstore.web.RequestMetadata;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;

@Service
public class EmailAuthService {
    private final EmailChallengeRepository challenges;
    private final UserAccountRepository users;
    private final PasswordEncoder encoder;
    private final PasswordPolicy passwords;
    private final EmailSender mail;
    private final AuthService auth;
    private final RequestMetadata metadata;
    private final AuditService audit;
    private final String from;
    private final SecureRandom random=new SecureRandom();
    private final Map<String,ArrayDeque<Instant>> ipRequests=new HashMap<>();
    public EmailAuthService(EmailChallengeRepository challenges,UserAccountRepository users,
        PasswordEncoder encoder,PasswordPolicy passwords,EmailSender mail,AuthService auth,
        RequestMetadata metadata,AuditService audit,@Value("${app.mail.from}") String from) {
        this.challenges=challenges;this.users=users;this.encoder=encoder;this.passwords=passwords;
        this.mail=mail;this.auth=auth;this.metadata=metadata;this.audit=audit;this.from=from;
    }
    /** Ограничение по IP защищает также запросы на множество разных адресов. */
    private synchronized void throttleIp(Instant now) {
        ipRequests.values().forEach(q -> { while(!q.isEmpty() && q.peek().isBefore(now.minusSeconds(3600))) q.remove(); });
        ipRequests.values().removeIf(Deque::isEmpty);
        if(ipRequests.size()>10000) throw new RateLimitException();
        var queue=ipRequests.computeIfAbsent(metadata.ipAddress(),k->new ArrayDeque<>());
        if(queue.size()>=10) throw new RateLimitException();
        queue.add(now);
    }
    @Transactional
    public CodeSent request(CodeRequest request) {
        Instant now=Instant.now(); throttleIp(now);
        String email=request.email().trim().toLowerCase(Locale.ROOT);
        if(request.purpose()==Purpose.REGISTER) {
            if(request.displayName()==null || request.displayName().isBlank()) throw new BadRequestException("Укажите имя");
            passwords.validate(request.password(),email);
        }
        EmailChallenge c=challenges.findByEmail(email).orElseGet(()->new EmailChallenge(email));
        if(c.sentAt!=null && c.sentAt.plusSeconds(60).isAfter(now)) throw new RateLimitException();
        if(c.windowStart==null || c.windowStart.plusSeconds(3600).isBefore(now)) { c.windowStart=now;c.sendCount=0; }
        if(c.sendCount>=5) throw new RateLimitException();
        String code=String.format(Locale.ROOT,"%06d",random.nextInt(1000000));
        c.purpose=request.purpose().name();c.codeHash=encoder.encode(code);c.attempts=0;c.consumed=false;
        c.sentAt=now;c.expiresAt=now.plusSeconds(300);c.sendCount++;
        c.displayName=request.displayName()==null?null:request.displayName().trim();
        c.passwordHash=request.purpose()==Purpose.REGISTER?encoder.encode(request.password()):null;
        challenges.saveAndFlush(c);
        // Одинаковый ответ для существующих и отсутствующих аккаунтов.
        boolean exists=users.existsByEmailIgnoreCase(email);
        if((request.purpose()==Purpose.LOGIN && exists) || (request.purpose()==Purpose.REGISTER && !exists)) {
            String subject="SPIN — код для "+(request.purpose()==Purpose.REGISTER?"регистрации":"входа");
            String text="Ваш код: "+code+"\nКод действует 5 минут и может быть использован один раз.\nЕсли вы не запрашивали код, проигнорируйте письмо.";
            mail.send(from,email,subject,text);
        }
        return new CodeSent("Если адрес подходит для выбранного действия, письмо с кодом отправлено",60,300);
    }
    /** Не откатываем счётчик неверных кодов при отказе во входе. Блокировка строки исключает повторное использование. */
    @Transactional(noRollbackFor=AuthenticationFailedException.class)
    public AuthResponse verify(VerifyRequest request) {
        String email=request.email().trim().toLowerCase(Locale.ROOT);
        EmailChallenge c=challenges.findByEmail(email).orElseThrow(AuthenticationFailedException::new);
        if(c.consumed || c.attempts>=5 || !c.expiresAt.isAfter(Instant.now()) || !c.purpose.equals(request.purpose().name()))
            throw new AuthenticationFailedException();
        c.attempts++;
        if(!encoder.matches(request.code(),c.codeHash)) throw new AuthenticationFailedException();
        UserAccount user=users.findByEmailIgnoreCase(email).orElse(null);
        if(request.purpose()==Purpose.REGISTER) {
            if(user!=null) throw new AuthenticationFailedException();
            user=new UserAccount(UUID.randomUUID(),email,c.displayName,c.passwordHash,Set.of(Role.VIEWER));
            user.setPasswordChangeRequired(false);users.saveAndFlush(user);
            audit.recordAs(user,"EMAIL_REGISTERED","Подтверждена регистрация по email");
        }
        if(user==null || !user.isEnabled() || (user.getLockedUntil()!=null && user.getLockedUntil().isAfter(Instant.now())))
            throw new AuthenticationFailedException();
        c.consumed=true;c.passwordHash=null;
        audit.recordAs(user,"EMAIL_LOGIN_SUCCESS","Вход по одноразовому коду");
        return auth.issueSession(user);
    }
}
