package com.example.videobrowseassistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SafetyTermsTest {
    private static String sharedVocabulary() throws IOException {
        return new String(Files.readAllBytes(PlatformTest.projectFile(
                "../gateway/config/safety-terms.json", "gateway/config/safety-terms.json").toPath()),
                StandardCharsets.UTF_8);
    }

    private static List<String> array(String json, String field) {
        Matcher block = Pattern.compile("\"" + field + "\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(json);
        List<String> values = new ArrayList<>();
        if (block.find()) {
            Matcher item = Pattern.compile("\"([^\"]*)\"").matcher(block.group(1));
            while (item.find()) {
                values.add(item.group(1));
            }
        }
        return values;
    }

    @Test
    public void androidMirrorMatchesTheGatewayVocabulary() throws IOException {
        String json = sharedVocabulary();
        Matcher version = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        assertTrue(version.find());
        assertEquals(version.group(1), SafetyTerms.VERSION);
        assertEquals(array(json, "block"), SafetyTerms.BLOCK);
        assertEquals(array(json, "review"), SafetyTerms.REVIEW);
        assertEquals(array(json, "allow_phrases"), SafetyTerms.ALLOW_PHRASES);
    }

    @Test
    public void classificationHonorsAllowPhrasesAndLevels() {
        assertEquals(SafetyTerms.Level.OK, SafetyTerms.classify("反诈骗知识科普：如何识别骗局"));
        assertEquals(SafetyTerms.Level.BLOCK, SafetyTerms.classify("这段内容包含诈骗话术"));
        assertEquals(SafetyTerms.Level.REVIEW, SafetyTerms.classify("电影里的暴力镜头讨论"));
        assertEquals(SafetyTerms.Level.OK, SafetyTerms.classify("暴力美学的镜头语言"));
        assertEquals(SafetyTerms.Level.REVIEW, SafetyTerms.classify("想要资料可以加V"));
        assertEquals(SafetyTerms.Level.OK, SafetyTerms.classify(null));
        assertTrue(SafetyTerms.isBlocked("赌博"));
        assertFalse(SafetyTerms.isBlocked("暴力"));
        assertTrue(SafetyTerms.isUnsafeForOutput("暴力"));
    }
}
