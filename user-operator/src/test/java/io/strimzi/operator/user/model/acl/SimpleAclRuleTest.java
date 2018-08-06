/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.user.model.acl;

import io.strimzi.api.kafka.model.AclOperation;
import io.strimzi.api.kafka.model.AclResourcePatternType;
import io.strimzi.api.kafka.model.AclRule;
import io.strimzi.api.kafka.model.AclRuleResource;
import io.strimzi.api.kafka.model.AclRuleTopicResource;
import io.strimzi.api.kafka.model.AclRuleType;

import kafka.security.auth.Acl;
import kafka.security.auth.Allow$;
import kafka.security.auth.Read$;
import kafka.security.auth.Resource;
import kafka.security.auth.Topic$;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.security.auth.KafkaPrincipal;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SimpleAclRuleTest {
    private static AclRuleResource crdResource;
    private static SimpleAclRuleResource resource = new SimpleAclRuleResource("my-topic", SimpleAclRuleResourceType.TOPIC, AclResourcePatternType.LITERAL);
    private static Resource kafkaResource = new Resource(Topic$.MODULE$, "my-topic", PatternType.LITERAL);
    private static KafkaPrincipal kafkaPrincipal = new KafkaPrincipal("User", "my-user");

    static {
        crdResource = new AclRuleTopicResource();
        ((AclRuleTopicResource) crdResource).setName("my-topic");
        ((AclRuleTopicResource) crdResource).setPatternType(AclResourcePatternType.LITERAL);
    }

    @Test
    public void testFromCrd()   {
        AclRule rule = new AclRule();
        rule.setType(AclRuleType.ALLOW);
        rule.setResource(crdResource);
        rule.setHost("127.0.0.1");
        rule.setOperation(AclOperation.READ);

        SimpleAclRule simple = SimpleAclRule.fromCrd(rule);
        assertEquals(AclOperation.READ, simple.getOperation());
        assertEquals(AclRuleType.ALLOW, simple.getType());
        assertEquals("127.0.0.1", simple.getHost());
        assertEquals(resource, simple.getResource());
    }

    @Test
    public void testToKafka()   {
        SimpleAclRule strimzi = new SimpleAclRule(AclRuleType.ALLOW, resource, "127.0.0.1", AclOperation.READ);
        Acl kafka = new Acl(kafkaPrincipal, Allow$.MODULE$, "127.0.0.1", Read$.MODULE$);
        assertEquals(kafka, strimzi.toKafkaAcl(kafkaPrincipal));
    }

    @Test
    public void testFromKafka()   {
        SimpleAclRule strimzi = new SimpleAclRule(AclRuleType.ALLOW, resource, "127.0.0.1", AclOperation.READ);
        Acl kafka = new Acl(kafkaPrincipal, Allow$.MODULE$, "127.0.0.1", Read$.MODULE$);
        assertEquals(strimzi, SimpleAclRule.fromKafkaAcl(resource, kafka));
    }

    @Test
    public void testRoundtrip()   {
        Acl kafka = new Acl(kafkaPrincipal, Allow$.MODULE$, "127.0.0.1", Read$.MODULE$);
        assertEquals(kafka, SimpleAclRule.fromKafkaAcl(resource, kafka).toKafkaAcl(kafkaPrincipal));
    }

    @Test
    public void testPassthrough()   {
        AclRule rule = new AclRule();
        rule.setType(AclRuleType.ALLOW);
        rule.setResource(crdResource);
        rule.setHost("127.0.0.1");
        rule.setOperation(AclOperation.READ);

        Acl kafka = new Acl(kafkaPrincipal, Allow$.MODULE$, "127.0.0.1", Read$.MODULE$);

        assertEquals(kafka, SimpleAclRule.fromCrd(rule).toKafkaAcl(kafkaPrincipal));
    }
}
