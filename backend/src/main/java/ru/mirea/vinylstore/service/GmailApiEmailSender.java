package ru.mirea.vinylstore.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;

@Service
public class GmailApiEmailSender implements EmailSender {
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String SEND_URL = "https://gmail.googleapis.com/gmail/v1/users/me/messages/send";

    private final RestClient http;
    private final String clientId;
    private final String clientSecret;
    private final String refreshToken;
    private volatile CachedToken cachedToken;

    public GmailApiEmailSender(RestClient.Builder restClientBuilder,
            @Value("${app.mail.gmail.client-id:}") String clientId,
            @Value("${app.mail.gmail.client-secret:}") String clientSecret,
            @Value("${app.mail.gmail.refresh-token:}") String refreshToken) {
        this.http = restClientBuilder.build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.refreshToken = refreshToken;
    }

    @Override
    public void send(String from, String to, String subject, String text) {
        try {
            String rawMessage = encodeMessage(from, to, subject, text);
            http.post()
                    .uri(SEND_URL)
                    .headers(headers -> headers.setBearerAuth(accessToken()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("raw", rawMessage))
                    .retrieve()
                    .toBodilessEntity();
        } catch (MailSendException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new MailSendException("Gmail API email delivery failed", ex);
        }
    }

    private String accessToken() {
        CachedToken current = cachedToken;
        if (current != null && current.expiresAt().isAfter(Instant.now().plusSeconds(60))) {
            return current.value();
        }
        synchronized (this) {
            current = cachedToken;
            if (current != null && current.expiresAt().isAfter(Instant.now().plusSeconds(60))) {
                return current.value();
            }
            return refreshAccessToken();
        }
    }

    private String refreshAccessToken() {
        if (clientId.isBlank() || clientSecret.isBlank() || refreshToken.isBlank()) {
            throw new MailSendException("Gmail OAuth is not configured");
        }

        var form = new LinkedMultiValueMap<String, String>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("refresh_token", refreshToken);
        form.add("grant_type", "refresh_token");
        try {
            TokenResponse response = http.post()
                    .uri(TOKEN_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
            if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
                throw new MailSendException("Gmail OAuth did not return an access token");
            }
            cachedToken = new CachedToken(response.accessToken(), Instant.now().plusSeconds(response.expiresIn()));
            return response.accessToken();
        } catch (RestClientException ex) {
            throw new MailSendException("Gmail OAuth token refresh failed", ex);
        }
    }

    private String encodeMessage(String from, String to, String subject, String text)
            throws jakarta.mail.MessagingException, IOException {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress(from));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, false));
        message.setSubject(subject, StandardCharsets.UTF_8.name());
        message.setText(text, StandardCharsets.UTF_8.name());
        message.saveChanges();
        var bytes = new ByteArrayOutputStream();
        message.writeTo(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
    }

    private record CachedToken(String value, Instant expiresAt) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn) { }
}