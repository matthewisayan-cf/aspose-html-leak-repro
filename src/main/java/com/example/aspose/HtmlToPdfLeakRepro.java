package com.example.aspose;

import com.aspose.html.Configuration;
import com.aspose.html.HTMLDocument;
import com.aspose.html.converters.Converter;
import com.aspose.html.drawing.Page;
import com.aspose.html.drawing.Size;
import com.aspose.html.drawing.Unit;
import com.aspose.html.saving.PdfSaveOptions;
import com.aspose.html.services.IUserAgentService;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.management.ObjectName;

/**
 * Minimal, dependency-free reproduction of an unbounded heap leak in Aspose.HTML for Java when a process performs many
 * sequential HTML-to-PDF conversions.
 *
 * <p>
 * Each conversion creates a fresh {@link Configuration} and {@link HTMLDocument} and disposes both in a finally block,
 * exactly as the Aspose documentation prescribes. Despite that, the <b>post-GC live heap grows linearly and without
 * plateau</b> (~280 KB retained per conversion in our environment), and a class histogram attributes the growth to
 * Aspose-internal static caches (obfuscated {@code com.aspose.html.utils.*} types and
 * {@code com.aspose.html.utils.ms.System.Drawing.Color}). With a bounded heap the process eventually throws
 * {@link OutOfMemoryError}.
 *
 * <p>
 * Usage: {@code ./gradlew run --args="<conversions> <sampleEvery> <warmup>"} (defaults: 2000 100 30). Place a valid
 * {@code Aspose.Total.lic} (or {@code Aspose.HTML.lic}) at the repo root to run licensed; otherwise it runs in
 * evaluation mode (the leak reproduces either way).
 */
public final class HtmlToPdfLeakRepro {

    private static final String SAMPLE_HTML_RESOURCE = "/sample.html";
    private static final String[] LICENSE_CANDIDATES = {"Aspose.Total.lic", "Aspose.HTML.lic"};

    /** Discards PDF bytes so the harness itself retains nothing and does not skew the heap reading. */
    private static final OutputStream NULL_SINK = new OutputStream() {
        @Override
        public void write(int b) {
            // discard
        }

        @Override
        public void write(byte[] b, int off, int len) {
            // discard
        }
    };

    private HtmlToPdfLeakRepro() {
        // utility
    }

    public static void main(String[] args) throws Exception {
        int conversions = args.length > 0 ? Integer.parseInt(args[0]) : 2000;
        int sampleEvery = args.length > 1 ? Integer.parseInt(args[1]) : 100;
        int warmup = args.length > 2 ? Integer.parseInt(args[2]) : 30;

        loadLicense();
        String html = loadSampleHtml();
        String baseUrl = new File(".").toURI().toString();

        System.out.printf(
                "Aspose.HTML for Java leak repro: conversions=%d sampleEvery=%d warmup=%d maxHeap=%dMB%n",
                conversions, sampleEvery, warmup, Runtime.getRuntime().maxMemory() / (1024 * 1024));

        for (int i = 0; i < warmup; i++) {
            convertOnce(html, baseUrl);
        }

        double baselineHeapMb = gcAndUsedHeapMb();
        Map<String, long[]> baselineHisto = classHistogram();
        System.out.println("conversions   liveHeapMB(post-GC)");
        System.out.printf("%11d   %10.1f%n", 0, baselineHeapMb);

        double lastHeapMb = baselineHeapMb;
        for (int i = 1; i <= conversions; i++) {
            convertOnce(html, baseUrl);
            if (i % sampleEvery == 0) {
                lastHeapMb = gcAndUsedHeapMb();
                System.out.printf("%11d   %10.1f%n", i, lastHeapMb);
            }
        }

        Map<String, long[]> finalHisto = classHistogram();
        printTopGrowers(baselineHisto, finalHisto);

        double deltaMb = lastHeapMb - baselineHeapMb;
        System.out.println("----------------------------------------------------");
        System.out.printf("baseline live heap = %.1f MB%n", baselineHeapMb);
        System.out.printf("final live heap    = %.1f MB%n", lastHeapMb);
        System.out.printf("delta              = %+.1f MB over %d conversions%n", deltaMb, conversions);
        System.out.printf("per conversion     = %+.1f KB/conv%n", deltaMb * 1024.0 / conversions);
    }

    /**
     * Performs one HTML-to-PDF conversion using the documented create/convert/dispose pattern. A new Configuration and
     * HTMLDocument are created and both are disposed in finally — yet retained heap still grows across calls.
     */
    private static void convertOnce(String html, String baseUrl) {
        Configuration configuration = null;
        HTMLDocument document = null;
        try {
            configuration = new Configuration();
            IUserAgentService userAgentService = configuration.getService(IUserAgentService.class);
            if (userAgentService != null) {
                userAgentService.setCharSet(StandardCharsets.UTF_8.name());
            }

            document = new HTMLDocument(html, baseUrl, configuration);

            PdfSaveOptions saveOptions = new PdfSaveOptions();
            saveOptions.setTaggedPdf(true);
            Size letter = new Size(Unit.fromInches(8.5), Unit.fromInches(11));
            saveOptions.getPageSetup().setAnyPage(new Page(letter));

            Converter.convertHTML(document, saveOptions, NULL_SINK);
        } catch (Exception ex) {
            throw new RuntimeException("Conversion failed", ex);
        } finally {
            if (document != null) {
                try {
                    document.dispose();
                } catch (Exception ignored) {
                    // best effort
                }
            }
            if (configuration != null) {
                try {
                    configuration.dispose();
                } catch (Exception ignored) {
                    // best effort
                }
            }
        }
    }

    private static void loadLicense() {
        boolean loaded = false;
        for (int i = 0; i < LICENSE_CANDIDATES.length && !loaded; i++) {
            File lic = new File(LICENSE_CANDIDATES[i]);
            if (lic.isFile()) {
                try {
                    com.aspose.html.License license = new com.aspose.html.License();
                    license.setLicense(lic.getAbsolutePath());
                    System.out.println("Loaded Aspose license: " + lic.getName());
                    loaded = true;
                } catch (Exception ex) {
                    System.out.println("Failed to load license " + lic.getName() + ": " + ex.getMessage());
                }
            }
        }
        if (!loaded) {
            System.out.println("No Aspose license found (looked for " + String.join(", ", LICENSE_CANDIDATES)
                    + "); running in EVALUATION mode. The leak reproduces either way.");
        }
    }

    private static String loadSampleHtml() throws IOException {
        try (InputStream in = HtmlToPdfLeakRepro.class.getResourceAsStream(SAMPLE_HTML_RESOURCE)) {
            if (in == null) {
                throw new IOException("Missing classpath resource " + SAMPLE_HTML_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Forces several GC cycles, then returns used heap in MB — an approximation of post-GC live heap. */
    private static double gcAndUsedHeapMb() {
        for (int i = 0; i < 4; i++) {
            System.gc();
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        long used = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        return used / (1024.0 * 1024.0);
    }

    /** Captures a live-object class histogram via the JVM DiagnosticCommand MBean: class -> [bytes, instances]. */
    private static Map<String, long[]> classHistogram() {
        Map<String, long[]> result = new HashMap<>();
        try {
            ObjectName name = new ObjectName("com.sun.management:type=DiagnosticCommand");
            String out = (String) ManagementFactory.getPlatformMBeanServer()
                    .invoke(name, "gcClassHistogram", new Object[] {null}, new String[] {"[Ljava.lang.String;"});
            for (String line : out.split("\n")) {
                String trimmed = line.trim();
                String[] parts = trimmed.split("\\s+");
                // Expected columns: <num> <#instances> <#bytes> <class name>
                if (parts.length >= 4 && parts[0].matches("\\d+:")) {
                    long instances = Long.parseLong(parts[1]);
                    long bytes = Long.parseLong(parts[2]);
                    result.put(parts[3], new long[] {bytes, instances});
                }
            }
        } catch (Exception ex) {
            System.out.println("Class histogram unavailable: " + ex.getMessage());
        }
        return result;
    }

    /** Prints the classes whose retained bytes grew the most between baseline and final histograms. */
    private static void printTopGrowers(Map<String, long[]> baseline, Map<String, long[]> end) {
        if (end.isEmpty()) {
            return;
        }
        List<String[]> rows = new ArrayList<>();
        for (Map.Entry<String, long[]> e : end.entrySet()) {
            long[] b = baseline.getOrDefault(e.getKey(), new long[] {0, 0});
            long deltaBytes = e.getValue()[0] - b[0];
            long deltaInstances = e.getValue()[1] - b[1];
            if (deltaBytes > 0) {
                rows.add(new String[] {
                    String.valueOf(deltaBytes),
                    String.format("%10.1f", deltaBytes / 1024.0),
                    String.valueOf(deltaInstances),
                    e.getKey()
                });
            }
        }
        rows.sort(Comparator.comparingLong((String[] r) -> Long.parseLong(r[0])).reversed());
        System.out.println("--- top retained-byte growers (final - baseline, live objects) ---");
        System.out.printf("%12s   %16s   %s%n", "deltaKB", "deltaInstances", "class");
        for (int i = 0; i < Math.min(15, rows.size()); i++) {
            String[] r = rows.get(i);
            System.out.printf("%12s   %16s   %s%n", r[1], r[2], r[3]);
        }
    }
}
