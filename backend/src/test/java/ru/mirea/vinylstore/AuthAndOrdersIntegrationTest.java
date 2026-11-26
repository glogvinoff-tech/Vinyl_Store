package ru.mirea.vinylstore;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.mirea.vinylstore.domain.*;
import ru.mirea.vinylstore.repository.*;
import java.util.*;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class AuthAndOrdersIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserAccountRepository users;
    @Autowired VinylRecordRepository records;
    @Autowired EmailChallengeRepository challenges;
    @Autowired StoreOrderRepository orders;
    @Autowired AuditEventRepository auditEvents;
    @Autowired PasswordEncoder encoder;
    @MockitoBean ru.mirea.vinylstore.service.EmailSender mail;
    private String email(){return "test"+UUID.randomUUID().toString().replace("-","")+"@gmail.com";}
    private JsonNode postJson(String path,Object body,int status)throws Exception {
        var result=mvc.perform(post(path).contentType("application/json").content(json.writeValueAsBytes(body)))
            .andExpect(status().is(status)).andReturn();
        return result.getResponse().getContentAsString().isBlank()?json.nullNode():json.readTree(result.getResponse().getContentAsString());
    }
    private JsonNode postJson(String path,Object body,int status,String token)throws Exception {
        var result=mvc.perform(post(path).header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsBytes(body)))
            .andExpect(status().is(status)).andReturn();
        return result.getResponse().getContentAsString().isBlank()?json.nullNode():json.readTree(result.getResponse().getContentAsString());
    }
    private String login(Role role)throws Exception {
        String email=email();UserAccount u=new UserAccount(UUID.randomUUID(),email,"Тест",encoder.encode("TestPassw0rd!123"),Set.of(role));
        u.setPasswordChangeRequired(false);users.saveAndFlush(u);
        return postJson("/api/auth/login",Map.of("email",email,"password","TestPassw0rd!123","clientType","NATIVE"),200).get("accessToken").asText();
    }
    @Test void gmailRegistrationCodeIsHashedOneUseAndLimitsRetries()throws Exception {
        String email=email();postJson("/api/auth/email/request",Map.of("email",email,"purpose","REGISTER","displayName","Покупатель","password","TestPassw0rd!123"),200);
        ArgumentCaptor<String> recipient=ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text=ArgumentCaptor.forClass(String.class);
        verify(mail).send(anyString(),recipient.capture(),anyString(),text.capture());
        assertThat(recipient.getValue()).isEqualTo(email);
        var matcher=java.util.regex.Pattern.compile("[0-9]{6}").matcher(text.getValue());assertThat(matcher.find()).isTrue();String code=matcher.group();
        assertThat(challenges.findById(email).orElseThrow().codeHash).isNotEqualTo(code);
        assertThat(users.existsByEmailIgnoreCase(email)).isFalse();
        postJson("/api/auth/email/request",Map.of("email",email,"purpose","REGISTER","displayName","Покупатель","password","TestPassw0rd!123"),429);
        var payload=Map.of("email",email,"purpose","REGISTER","code",code,"clientType","WEB");
        var result=mvc.perform(post("/api/auth/email/verify").contentType("application/json").content(json.writeValueAsBytes(payload)))
          .andExpect(status().isOk()).andReturn();
        JsonNode session=json.readTree(result.getResponse().getContentAsString());
        assertThat(session.hasNonNull("refreshToken")).isFalse();
        assertThat(result.getResponse().getHeader("Set-Cookie")).contains("HttpOnly");
        assertThat(users.findByEmailIgnoreCase(email).orElseThrow().getRoles()).containsExactly(Role.VIEWER);
        postJson("/api/auth/email/verify",payload,401);
    }
    @Test void failedCodeAttemptsPersistAndBlockEvenCorrectCode()throws Exception {
        String email=email();postJson("/api/auth/email/request",Map.of("email",email,"purpose","REGISTER","displayName","Покупатель","password","TestPassw0rd!123"),200);
        for(int i=0;i<5;i++)postJson("/api/auth/email/verify",Map.of("email",email,"purpose","REGISTER","code","abcdef"),400);
        // Invalid DTOs do not count as code attempts. Six-digit wrong codes do.
        var c=challenges.findById(email).orElseThrow();c.codeHash=encoder.encode("123456");challenges.saveAndFlush(c);
        for(int i=0;i<5;i++)postJson("/api/auth/email/verify",Map.of("email",email,"purpose","REGISTER","code","000000"),401);
        assertThat(challenges.findById(email).orElseThrow().attempts).isEqualTo(5);
        postJson("/api/auth/email/verify",Map.of("email",email,"purpose","REGISTER","code","123456"),401);
    }
    @Test void failedDeliveryDoesNotSaveChallengeAndCanBeRetried() throws Exception {
        String email=email();
        var body=Map.of("email",email,"purpose","REGISTER","displayName","Покупатель","password","TestPassw0rd!123");
        doThrow(new org.springframework.mail.MailSendException("Gmail unavailable")).when(mail).send(anyString(),anyString(),anyString(),anyString());
        postJson("/api/auth/email/request",body,503);
        assertThat(challenges.findById(email)).isEmpty();
        assertThat(users.existsByEmailIgnoreCase(email)).isFalse();
        doNothing().when(mail).send(anyString(),anyString(),anyString(),anyString());
        postJson("/api/auth/email/request",body,200);
        assertThat(challenges.findById(email)).isPresent();
    }
    @Test void expiredCodeCannotRegisterAccount() throws Exception {
        String email=email();
        postJson("/api/auth/email/request",Map.of("email",email,"purpose","REGISTER","displayName","Покупатель","password","TestPassw0rd!123"),200);
        var c=challenges.findById(email).orElseThrow();
        c.codeHash=encoder.encode("123456");c.expiresAt=java.time.Instant.now().minusSeconds(1);challenges.saveAndFlush(c);
        postJson("/api/auth/email/verify",Map.of("email",email,"purpose","REGISTER","code","123456"),401);
        assertThat(users.existsByEmailIgnoreCase(email)).isFalse();
    }
    @Test void emailLoginUsesExistingAccountAndCannotBypassDisabledFlag() throws Exception {
        String email=email();
        var user=new UserAccount(UUID.randomUUID(),email,"Покупатель",encoder.encode("TestPassw0rd!123"),Set.of(Role.VIEWER));
        user.setPasswordChangeRequired(false);users.saveAndFlush(user);
        postJson("/api/auth/email/request",Map.of("email",email,"purpose","LOGIN"),200);
        var c=challenges.findById(email).orElseThrow();c.codeHash=encoder.encode("123456");challenges.saveAndFlush(c);
        user=users.findById(user.getId()).orElseThrow();user.setEnabled(false);users.saveAndFlush(user);
        var body=Map.of("email",email,"purpose","LOGIN","code","123456","clientType","WEB");
        postJson("/api/auth/email/verify",body,401);
        user=users.findById(user.getId()).orElseThrow();user.setEnabled(true);users.saveAndFlush(user);
        postJson("/api/auth/email/verify",body,200);
        postJson("/api/auth/email/verify",body,401);
    }
    @Test void rolesVersionAndArchiveAreEnforcedByServer()throws Exception {
        String viewer=login(Role.VIEWER),manager=login(Role.MANAGER);
        VinylRecord r=new VinylRecord(UUID.randomUUID(),"TEST-"+UUID.randomUUID(),"Album",2024);r.setArtist("Artist");records.saveAndFlush(r);
        var body=new LinkedHashMap<String,Object>();body.put("externalId","TEST-"+UUID.randomUUID());body.put("albumTitle","Album");body.put("artist","Artist");body.put("customerName","Покупатель");body.put("recordId",r.getId());body.put("status","NEW");body.put("totalPrice",4990);body.put("paid",false);body.put("tags",List.of("test"));body.put("quantity",1);
        mvc.perform(post("/api/orders").header("Authorization","Bearer "+viewer).contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().isForbidden());
        var response=mvc.perform(post("/api/orders").header("Authorization","Bearer "+manager).contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().isCreated()).andReturn();
        JsonNode order=json.readTree(response.getResponse().getContentAsString());String id=order.get("id").asText();
        body.put("version",order.get("version").asLong());body.put("totalPrice",5990);
        mvc.perform(put("/api/orders/"+id).header("Authorization","Bearer "+manager).contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().isOk());
        mvc.perform(put("/api/orders/"+id).header("Authorization","Bearer "+manager).contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().isConflict());
        mvc.perform(delete("/api/orders/"+id).header("Authorization","Bearer "+manager)).andExpect(status().isOk()).andExpect(jsonPath("$.archived").value(true));
        mvc.perform(post("/api/orders/"+id+"/restore").header("Authorization","Bearer "+manager)).andExpect(status().isOk()).andExpect(jsonPath("$.archived").value(false));
        mvc.perform(get("/api/admin/audit").header("Authorization","Bearer "+viewer)).andExpect(status().isForbidden());
    }
    @Test void refreshRotatesAndLogoutRevokes()throws Exception {
        String email=email();var u=new UserAccount(UUID.randomUUID(),email,"Тест",encoder.encode("TestPassw0rd!123"),Set.of(Role.VIEWER));u.setPasswordChangeRequired(false);users.saveAndFlush(u);
        var s=postJson("/api/auth/login",Map.of("email",email,"password","TestPassw0rd!123"),200);
        var next=postJson("/api/auth/refresh",Map.of("refreshToken",s.get("refreshToken").asText()),200);
        postJson("/api/auth/logout",Map.of("refreshToken",next.get("refreshToken").asText()),204);
        postJson("/api/auth/refresh",Map.of("refreshToken",next.get("refreshToken").asText()),401);
    }
    @Test void pickupCheckoutRollsBackAllStockWhenOneLineUnavailable() throws Exception {
        String token = login(Role.VIEWER);
        var ids = new ArrayList<>(List.of(UUID.randomUUID(), UUID.randomUUID()));
        ids.sort(Comparator.naturalOrder());
        for (UUID id : ids) {
            var record = new VinylRecord(id, "STOCK-" + id, "Stock", 2024);
            record.setArtist("Artist");
            record.setStock(1);
            records.saveAndFlush(record);
        }
        var lines = List.of(Map.of("recordId", ids.get(0), "quantity", 1),
                Map.of("recordId", ids.get(1), "quantity", 2));
        postJson("/api/shop/checkout", Map.of("customerName", "Покупатель", "lines", lines), 409, token);
        assertThat(records.findAllById(ids)).allSatisfy(record -> assertThat(record.getStock()).isEqualTo(1));
        mvc.perform(get("/api/shop/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    }

    @Test void pickupCheckoutCreatesAnUnpaidOrderInHistory() throws Exception {
        String token = login(Role.VIEWER);
        String title = "А".repeat(200);
        var record = new VinylRecord(UUID.randomUUID(), "PICKUP-" + UUID.randomUUID(), title, 2024);
        record.setArtist("Artist");
        record.setStock(1);
        records.saveAndFlush(record);
        var body = Map.of("customerName", "Покупатель", "phone", "+79991234567",
                "lines", List.of(Map.of("recordId", record.getId(), "quantity", 1)));

        var order = postJson("/api/shop/checkout", body, 200, token).get(0);
        assertThat(order.get("status").asText()).isEqualTo("NEW");
        assertThat(order.get("paid").asBoolean()).isFalse();
        assertThat(order.get("albumTitle").asText()).isEqualTo(title);
        assertThat(records.findById(record.getId()).orElseThrow().getStock()).isZero();
        mvc.perform(get("/api/shop/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(order.get("id").asText()));
    }

    @Test void csvPreviewCountsRowsAndRoundTripsBomAndQuantity() throws Exception {
        String manager=login(Role.MANAGER);
        VinylRecord r=new VinylRecord(UUID.randomUUID(),"CSV-"+UUID.randomUUID(),"CSV Album",2024);r.setArtist("Artist");records.saveAndFlush(r);
        String csv="\ufeffexternalId,artist,albumTitle,customerName,email,phone,orderedOn,record,status,totalPrice,paid,tags,quantity\n"
            +"CSV-"+UUID.randomUUID()+",Artist,CSV Album,Покупатель,test@gmail.com,+79991234567,2026-01-01,"+r.getCode()+",NEW,4990,false,test,2\n";
        var file=new org.springframework.mock.web.MockMultipartFile("file","orders.csv","text/csv",csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/orders/import/preview").file(file).header("Authorization","Bearer "+manager))
            .andExpect(status().isOk()).andExpect(jsonPath("$.validRows").value(1)).andExpect(jsonPath("$.invalidRows").value(0));
        mvc.perform(multipart("/api/orders/import/apply").file(file).header("Authorization","Bearer "+manager))
            .andExpect(status().isOk()).andExpect(jsonPath("$.created").value(1));
        var exported=mvc.perform(get("/api/orders/export").param("q","record:"+r.getCode()).header("Authorization","Bearer "+manager))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        String text=new String(exported,java.nio.charset.StandardCharsets.UTF_8);
        assertThat(text).startsWith("\ufeffexternalId").contains("quantity").contains("CSV Album");
        var exportedFile=new org.springframework.mock.web.MockMultipartFile("file","export.csv","text/csv",exported);
        mvc.perform(multipart("/api/orders/import/preview").file(exportedFile).param("mode","UPSERT_BY_EXTERNAL_ID").header("Authorization","Bearer "+manager))
            .andExpect(status().isOk()).andExpect(jsonPath("$.validRows").value(1));
    }

    @Test void malformedCsvReturnsBadRequestWithoutApplyingEarlierValidRows() throws Exception {
        String manager=login(Role.MANAGER);
        VinylRecord r=new VinylRecord(UUID.randomUUID(),"CSV-"+UUID.randomUUID(),"CSV Album",2024);
        r.setArtist("Artist");records.saveAndFlush(r);
        String externalId="BROKEN-"+UUID.randomUUID();
        String csv="externalId,artist,albumTitle,customerName,email,phone,orderedOn,record,status,totalPrice,paid,tags,quantity\n"
            +externalId+",Artist,CSV Album,Покупатель,test@gmail.com,+79991234567,2026-01-01,"+r.getCode()+",NEW,4990,false,test,1\n"
            +"\"unclosed";
        var file=new org.springframework.mock.web.MockMultipartFile("file","broken.csv","text/csv",csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long before=orders.count();
        for(String endpoint:List.of("preview","apply")) {
            for(String mode:List.of("CREATE_ONLY","UPSERT_BY_EXTERNAL_ID")) {
                mvc.perform(multipart("/api/orders/import/"+endpoint).file(file).param("mode",mode)
                        .header("Authorization","Bearer "+manager))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                    .andExpect(jsonPath("$.message").value("Некорректная структура CSV или ошибка чтения файла"));
                assertThat(orders.count()).isEqualTo(before);
                assertThat(orders.existsByExternalIdIgnoreCase(externalId)).isFalse();
            }
        }
    }

    @Test void csvExportPersistsAuditWithActorFilterAndCorrelationId() throws Exception {
        String manager=login(Role.MANAGER);
        String correlation="csv-export-"+UUID.randomUUID();
        String query="record:CSV-"+UUID.randomUUID();
        mvc.perform(get("/api/orders/export").param("q",query)
                .header("Authorization","Bearer "+manager).header("X-Correlation-ID",correlation))
            .andExpect(status().isOk()).andExpect(header().string("X-Correlation-ID",correlation));
        var events=auditEvents.findAll().stream().filter(e->correlation.equals(e.getCorrelationId())).toList();
        assertThat(events).hasSize(1);
        var event=events.getFirst();
        assertThat(event.getEventType()).isEqualTo("ORDER_CSV_EXPORTED");
        assertThat(event.getActorId()).isNotNull();
        assertThat(event.getActorEmail()).isEqualTo(users.findById(event.getActorId()).orElseThrow().getEmail());
        assertThat(json.readTree(event.getDetailsJson()).get("query").asText()).isEqualTo(query);
    }

    @Test void temporaryPasswordAndDisabledAccountCannotUseWorkingApi() throws Exception {
        String email=email();var u=new UserAccount(UUID.randomUUID(),email,"Временный",encoder.encode("TestPassw0rd!123"),Set.of(Role.VIEWER));users.saveAndFlush(u);
        String token=postJson("/api/auth/login",Map.of("email",email,"password","TestPassw0rd!123"),200).get("accessToken").asText();
        mvc.perform(get("/api/orders").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        u=users.findById(u.getId()).orElseThrow();u.setEnabled(false);users.saveAndFlush(u);
        postJson("/api/auth/login",Map.of("email",email,"password","TestPassw0rd!123"),401);
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    }

    @Test void reactRoutesDoNotExposeProtectedApiOrMaskUnknownPages() throws Exception {
        mvc.perform(get("/catalog")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        mvc.perform(get("/albums/00000000-0000-0000-0000-000000000001")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        mvc.perform(get("/cart")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/page-that-does-not-exist")).andExpect(status().isNotFound());
    }


    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test void adminCanUploadPhotosAndCheckoutUsesPersistedDiscount() throws Exception {
        jdbc.execute("CREATE TABLE IF NOT EXISTS catalog_image (id UUID PRIMARY KEY, content BYTEA NOT NULL)");
        String admin=login(Role.ADMIN), buyer=login(Role.VIEWER);
        var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);
        var file=new org.springframework.mock.web.MockMultipartFile("file","photo.png","image/png",bytes.toByteArray());
        mvc.perform(multipart("/api/images").file(file).header("Authorization","Bearer "+buyer)).andExpect(status().isForbidden());
        var upload=mvc.perform(multipart("/api/images").file(file).header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andReturn();
        String url=json.readTree(upload.getResponse().getContentAsString()).get("url").asText();
        mvc.perform(get(url)).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        var body=new LinkedHashMap<String,Object>();
        body.put("code","NEW-"+UUID.randomUUID());body.put("name","Test edition");body.put("artist","Artist");
        body.put("price",1000);body.put("stock",5);body.put("discountPercent",15);
        body.put("tags",List.of("Цветной винил","Подарочные издания"));body.put("cover",url);body.put("images",List.of(url));
        var created=mvc.perform(post("/api/records").header("Authorization","Bearer "+admin).contentType("application/json").content(json.writeValueAsBytes(body)))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.salePrice").value(850)).andReturn();
        var record=json.readTree(created.getResponse().getContentAsString());
        String id=record.get("id").asText();
        mvc.perform(get("/api/catalog").param("q","Цветной винил"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content[*].id").value(org.hamcrest.Matchers.hasItem(id)));
        mvc.perform(post("/api/shop/checkout").header("Authorization","Bearer "+buyer).contentType("application/json")
            .content(json.writeValueAsBytes(Map.of("customerName","Buyer","phone","+79991234567","lines",List.of(Map.of("recordId",id,"quantity",3))))))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].totalPrice").value(2550))
            .andExpect(jsonPath("$[0].status").value("NEW")).andExpect(jsonPath("$[0].paid").value(false));
        assertThat(records.findById(UUID.fromString(id)).orElseThrow().getStock()).isEqualTo(2);
        body.put("discountPercent",0);body.put("version",records.findById(UUID.fromString(id)).orElseThrow().getVersion());
        mvc.perform(put("/api/records/"+id).header("Authorization","Bearer "+admin).contentType("application/json").content(json.writeValueAsBytes(body)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.salePrice").value(1000));
        body.put("discountPercent",101);
        mvc.perform(post("/api/records").header("Authorization","Bearer "+admin).contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().isBadRequest());
        var invalid=new org.springframework.mock.web.MockMultipartFile("file","photo.png","image/png","not an image".getBytes());
        mvc.perform(multipart("/api/images").file(invalid).header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
    }
}
