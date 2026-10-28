package com.example.videobrowseassistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlatformTest {
    /** Gradle runs unit tests from the module directory; the sandbox script from the repo root. */
    static File projectFile(String moduleRelative, String rootRelative) {
        File fromModule = new File(moduleRelative);
        return fromModule.exists() ? fromModule : new File(rootRelative);
    }

    @Test
    public void manifestQueriesListExactlyTheSupportedPlatforms() throws IOException {
        File manifest = projectFile("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml");
        String xml = new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8);
        String queries = xml.substring(xml.indexOf("<queries>"), xml.indexOf("</queries>"));
        Set<String> declared = new TreeSet<>();
        Matcher matcher = Pattern.compile("<package\\s+android:name=\"([^\"]+)\"").matcher(queries);
        while (matcher.find()) {
            declared.add(matcher.group(1));
        }
        assertEquals(new TreeSet<>(Platform.packageNames()), declared);
    }

    @Test
    public void packageNamesAreUniqueAndResolvable() {
        Set<String> seen = new HashSet<>();
        for (Platform platform : Platform.values()) {
            assertEquals(true, seen.add(platform.packageName));
            assertSame(platform, Platform.fromPackage(platform.packageName));
        }
        assertNull(Platform.fromPackage("com.example.unknown"));
        assertNull(Platform.fromPackage(null));
        assertEquals("未知平台", Platform.labelFor("com.example.unknown"));
        assertEquals("B站", Platform.labelFor("tv.danmaku.bili"));
    }
}
