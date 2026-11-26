package ru.mirea.vinylstore.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class GmailApiEmailSenderTest {
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String SEND_URL = "https://gmail.googleapis.com/gmail/v1/users/me/messages/send";

    @Test
    void refreshesOAuthTokenAndSendsMessageThroughGmailApi() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(TOKEN_URL))
                .andExpect(method(POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andRespond(withSuccess("""
                        {"access_token":"test-access-token","expires_in":3600,"token_type":"Bearer"}
                        """, MediaType.APPLICATION_JSON));
        for (int i = 0; i < 2; i++) {
            server.expect(requestTo(SEND_URL))
                    .andExpect(method(POST))
                    .andExpect(header("Authorization", "Bearer test-access-token"))
                    .andExpect(jsonPath("$.raw").isNotEmpty())
                    .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        }

        var sender = new GmailApiEmailSender(builder, "client-id", "client-secret", "refresh-token");
        sender.send("sender@gmail.com", "recipient@gmail.com", "Subject", "Code: 123456");
        sender.send("sender@gmail.com", "recipient@gmail.com", "Subject", "Code: 654321");

        server.verify();
    }

    @Test
    void rejectsUnconfiguredOAuthWithoutMakingARequest() {
        var sender = new GmailApiEmailSender(RestClient.builder(), "", "", "");

        assertThatThrownBy(() -> sender.send("sender@gmail.com", "recipient@gmail.com", "Subject", "Text"))
                .isInstanceOf(MailSendException.class)
                .hasMessage("Gmail OAuth is not configured");
    }
}