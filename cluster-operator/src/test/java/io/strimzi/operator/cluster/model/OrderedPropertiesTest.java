/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.Assert;
import org.junit.Test;

public class OrderedPropertiesTest {

    private OrderedProperties createTestKeyValues() {
        return new OrderedProperties()
            .addPair("first", "1")
            .addPair("second", "2")
            .addPair("third", "3")
            .addPair("FOURTH", "4");
    }

    private void assertKeyOrder(OrderedProperties pairs, String... expected) {
        Assert.assertArrayEquals(expected, pairs.asMap().keySet().toArray(new String[] {}));
    }

    private void assertValueOrder(OrderedProperties pairs, String... expected) {
        Assert.assertArrayEquals(expected, pairs.asMap().values().toArray(new String[] {}));
    }

    @Test
    public void insertOrder() {
        assertKeyOrder(createTestKeyValues(), "first", "second", "third", "FOURTH");
    }

    @Test
    public void filter() {
        OrderedProperties pairs = createTestKeyValues();
        pairs.filter(k -> k.equalsIgnoreCase("second"));
        assertKeyOrder(pairs, "first", "third", "FOURTH");
    }

    @Test
    public void addMapPairs() {
        Map<String, String> additional = new LinkedHashMap<>();
        additional.put("fifth", "5");
        additional.put("first", "6");

        OrderedProperties pairs = createTestKeyValues().addMapPairs(additional);
        assertKeyOrder(pairs, "first", "second", "third", "FOURTH", "fifth");
        assertValueOrder(pairs, "6", "2", "3", "4", "5");
    }

    @Test
    public void addIterablePairs() {
        Map<String, Object> additional = new LinkedHashMap<>();
        additional.put("fifth", 5);
        additional.put("first", 6L);
        additional.put("second", true);
        additional.put("third", 2.3);
        additional.put("FOURTH", 4.6D);

        OrderedProperties pairs = createTestKeyValues().addIterablePairs(additional.entrySet());
        assertKeyOrder(pairs, "first", "second", "third", "FOURTH", "fifth");
        assertValueOrder(pairs, "6", "true", "2.3", "4.6", "5");
    }

    @Test
    public void addStringPairs() {
        OrderedProperties pairs = new OrderedProperties()
            .addStringPairs("first=1\nsecond=2\nthird=3\nFOURTH=4");
        assertValueOrder(pairs, "1", "2", "3", "4");
    }

    @Test
    public void asPairs() {
        Assert.assertEquals("first=1\nsecond=2\nthird=3\nFOURTH=4\n", createTestKeyValues().asPairs());
    }

    @Test
    public void asMap() {
        OrderedProperties pairs = createTestKeyValues();
        pairs.asMap().put("fifth", "5");
        assertKeyOrder(pairs, "first", "second", "third", "FOURTH", "fifth");
    }

    private static Properties loadProperties(String string) throws IOException {
        Properties actual = new Properties();
        actual.load(new StringReader(string));
        return actual;
    }

    static private void propertiesCompatibility(OrderedProperties pairs) throws IOException {
        Properties actual = loadProperties(pairs.asPairs());
        Map<String, String> expected = pairs.asMap();
        Assert.assertEquals(expected, actual);
    }

    static private Map<String, String> propertiesCompatibility(String pairs) throws IOException {
        Properties expected = loadProperties(pairs);
        Map<String, String> actual = new OrderedProperties().addStringPairs(pairs).asMap();
        Assert.assertEquals(expected, actual);
        return actual;
    }

    @Test
    public void simpleProperties() throws IOException {
        propertiesCompatibility(createTestKeyValues());
    }

    @Test
    public void propertiesContainsEscapes() throws IOException {
        OrderedProperties pairs = new OrderedProperties()
            .addPair(" leading", " leading")
            .addPair("trailing ", "trailing ")
            .addPair("with\\escape", "with\\escape\r\n\f\t")
            .addPair("two\nparts", " leading and trailing ")
            .addPair("\\", "\\")
            .addPair("", "");
        propertiesCompatibility(pairs);
    }

    @Test
    public void roundTrip() {
        OrderedProperties expected = new OrderedProperties()
            .addPair(" leading", " leading")
            .addPair("trailing ", "trailing ")
            .addPair("with\\escape", "with\\escape\r\n\f\t")
            .addPair("two\nparts", " leading and trailing ")
            .addPair("\\", "\\")
            .addPair("", "");
        Assert.assertEquals(expected, new OrderedProperties().addMapPairs(expected.asMap()));
    }

    @Test
    public void propertiesComments() throws IOException {
        Map<String, String> actual = propertiesCompatibility("! ignore\n  # ignore #\n bare_key");

        OrderedProperties expected = new OrderedProperties().addPair("bare_key", "");
        Assert.assertEquals(expected.asMap(), actual);
    }

    @Test
    public void propertiesLeadingSpaceAfterNewLine() throws IOException {
        Map<String, String> actual = propertiesCompatibility("key : multi\\\n \\\r\n \t line");

        OrderedProperties expected = new OrderedProperties().addPair("key", "multiline");
        Assert.assertEquals(expected.asMap(), actual);
    }

    @Test
    public void spacesBeforeAndAfterKey() throws IOException {
        Map<String, String> actual = propertiesCompatibility("  before: 1\nafter : 2");

        OrderedProperties expected = new OrderedProperties()
            .addPair("before", "1")
            .addPair("after", "2");
        Assert.assertEquals(expected.asMap(), actual);
    }

    @Test
    public void lineContinuation() throws IOException {
        Map<String, String> actual = propertiesCompatibility("cr:split\\\rvalue\nnl:split\\\nvalue\ncrnl:split\\\r\nvalue\nno:split");

        OrderedProperties expected = new OrderedProperties()
            .addPair("cr", "splitvalue")
            .addPair("nl", "splitvalue")
            .addPair("crnl", "splitvalue")
            .addPair("no", "split");
        Assert.assertEquals(expected.asMap(), actual);
    }

    @Test
    public void unicodeEscape() throws IOException {
        Map<String, String> actual = propertiesCompatibility("unicode=\\u0123X\\uAbBa");

        OrderedProperties expected = new OrderedProperties()
            .addPair("unicode", "\u0123X\uAbBa");
        Assert.assertEquals(expected.asMap(), actual);
    }
}
