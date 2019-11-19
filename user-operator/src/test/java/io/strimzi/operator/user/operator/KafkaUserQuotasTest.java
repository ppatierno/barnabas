/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.user.operator;

import io.strimzi.test.EmbeddedZooKeeper;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.Charset;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

public class KafkaUserQuotasTest {

    private static EmbeddedZooKeeper zkServer;

    private KafkaUserQuotas kuq;

    private JsonObject quotasJson;


    @BeforeAll
    public static void startZk() throws IOException, InterruptedException {
        zkServer = new EmbeddedZooKeeper();
    }

    @AfterAll
    public static void stopZk() {
        zkServer.close();
    }

    @BeforeEach
    public void createKUQ() {
        quotasJson = new JsonObject();
        quotasJson.put("consumer_byte_rate", "1000");
        quotasJson.put("producer_byte_rate", "2000");
        kuq = new KafkaUserQuotas(zkServer.getZkConnectString(), 6_000);
    }

    @Test
    public void normalCreate() {
        JsonObject quotasJson = new JsonObject();
        quotasJson.put("consumer_byte_rate", "1000");
        quotasJson.put("producer_byte_rate", "2000");
        kuq.createOrUpdate("normalCreate", quotasJson);
    }

    @Test
    public void doubleCreate() {
        kuq.createOrUpdate("doubleCreate", quotasJson);
        kuq.createOrUpdate("doubleCreate", quotasJson);
    }

    @Test
    public void normalDelete() {
        kuq.createOrUpdate("normalDelete", quotasJson);
        kuq.delete("normalDelete");
    }

    @Test
    public void doubleDelete() {
        kuq.createOrUpdate("doubleDelete", quotasJson);
        kuq.delete("doubleDelete");
        kuq.delete("doubleDelete");
    }

    @Test
    public void changePassword() {
        kuq.createOrUpdate("changePassword", quotasJson);
        quotasJson.put("producer_byte_rate", "8000");
        kuq.createOrUpdate("changePassword", quotasJson);
        quotasJson.remove("producer_byte_rate");
    }

    @Test
    public void userExists() {
        kuq.createOrUpdate("userExists", quotasJson);
        assertThat(kuq.exists("userExists"), is(true));
    }

    @Test
    public void userNotExists() {
        assertThat(kuq.exists("userNotExists"), is(false));
    }


    @Test
    public void testValidation()    {
        JsonObject valid = new JsonObject().put("version", 1);
        JsonObject invalid1 = new JsonObject();
        JsonObject invalid2 = new JsonObject().put("version", 2);

        kuq.validateJsonVersion(valid);

        try {
            kuq.validateJsonVersion(invalid1);
            fail("Invalid Json 1 didn't raised exception");
        } catch (RuntimeException e)    {
            // noop
        }

        try {
            kuq.validateJsonVersion(invalid2);
            fail("Invalid Json 2 didn't raised exception");
        } catch (RuntimeException e)    {
            // noop
        }
    }

    @Test
    public void testDeletion()  {
        JsonObject original = new JsonObject().put("version", 1).put("config", quotasJson);
        JsonObject updated = new JsonObject(new String(kuq.deleteUserJson(original.encode().getBytes(Charset.defaultCharset())), Charset.defaultCharset()));
        assertThat(updated.getJsonObject("config").getString("consumer_byte_rate"), is(nullValue()));

        original = new JsonObject().put("version", 1).put("config", new JsonObject().put("producer_byte_rate", "1000").put("consumer_byte_rate", "2000"));
        updated = new JsonObject(new String(kuq.deleteUserJson(original.encode().getBytes(Charset.defaultCharset())), Charset.defaultCharset()));
        assertThat(updated.getJsonObject("config").getString("producer_byte_rate"), is(nullValue()));
        assertThat(updated.getJsonObject("config").getString("consumer_byte_rate"), is(nullValue()));

        original = new JsonObject().put("version", 1).put("config", new JsonObject());
        updated = new JsonObject(new String(kuq.deleteUserJson(original.encode().getBytes(Charset.defaultCharset())), Charset.defaultCharset()));
        assertThat(updated.getJsonObject("config").getString("consumer_byte_rate"), is(nullValue()));
        assertThat(updated.getJsonObject("config").getString("producer_byte_rate"), is(nullValue()));

        original = new JsonObject().put("version", 1).put("config", new JsonObject().put("consumer_byte_rate", "1000"));
        updated = new JsonObject(new String(kuq.deleteUserJson(original.encode().getBytes(Charset.defaultCharset())), Charset.defaultCharset()));
        assertThat(updated.getJsonObject("config").getString("producer_byte_rate"), is(nullValue()));
        assertThat(updated.getJsonObject("config").getString("consumer_byte_rate"), is(nullValue()));
    }

    @Test
    public void testUpdate()  {
        JsonObject original = new JsonObject().put("version", 1).put("config", new JsonObject().put("consumer_byte_rate", "1000"));
        JsonObject updated = new JsonObject(new String(kuq.updateUserJson(original.encode().getBytes(Charset.defaultCharset()), new JsonObject().put("consumer_byte_rate", "2000")), Charset.defaultCharset()));
        assertThat(updated.getJsonObject("config").getString("consumer_byte_rate"), is(notNullValue()));
        assertThat(updated.getJsonObject("config").getString("consumer_byte_rate"), is("2000"));

        original = new JsonObject().put("version", 1).put("config", new JsonObject().put("consumer_byte_rate", "1000").put("producer_byte_rate", "2000"));
        updated = new JsonObject(new String(kuq.updateUserJson(original.encode().getBytes(Charset.defaultCharset()), new JsonObject().put("consumer_byte_rate", "3000").put("producer_byte_rate", "4000")), Charset.defaultCharset()));
        assertThat(updated.getJsonObject("config").getString("consumer_byte_rate"), is(notNullValue()));
        assertThat(updated.getJsonObject("config").getString("producer_byte_rate"), is(notNullValue()));
        assertThat(updated.getJsonObject("config").getString("producer_byte_rate"), is("4000"));
        assertThat(updated.getJsonObject("config").getString("consumer_byte_rate"), is("3000"));

        original = new JsonObject().put("version", 1).put("config", new JsonObject());
        updated = new JsonObject(new String(kuq.updateUserJson(original.encode().getBytes(Charset.defaultCharset()), new JsonObject().put("consumer_byte_rate", "1000")), Charset.defaultCharset()));
        assertThat(updated.getJsonObject("config").getString("consumer_byte_rate"), is(notNullValue()));
    }
}
