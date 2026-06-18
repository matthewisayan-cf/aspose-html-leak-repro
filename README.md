# Aspose.HTML for Java — unbounded heap growth on sequential HTML→PDF conversions

Minimal, self-contained reproduction of a memory leak in **Aspose.HTML for Java** when a single
process performs many sequential HTML-to-PDF conversions. Post-GC live heap grows **linearly with
no plateau** and is eventually fatal under a bounded heap (`OutOfMemoryError`).

Each conversion creates a fresh `Configuration` + `HTMLDocument` and **disposes both in a `finally`
block**, exactly as the Aspose documentation prescribes. The growth persists anyway, and a class
histogram attributes nearly all of it to **Aspose-internal static caches** — not application code.

## Environment

- Aspose.HTML for Java **26.5** (`com.aspose:aspose-html:26.5:jdk17`) — set in `build.gradle`, change to test other versions
- JDK 17
- Reproduced on macOS (arm64) and Linux (x86_64)

## How to run

```bash
# Optional: place a valid license at the repo root to run licensed (the leak reproduces in
# evaluation mode too). The .lic is gitignored and never committed.
#   cp /path/to/Aspose.Total.lic ./Aspose.Total.lic

# args: <conversions> <sampleEvery> <warmup>   (defaults: 2000 100 30)
./gradlew run --args="2000 100 30"
```

The harness:
1. loads the license (if present),
2. converts the same `src/main/resources/sample.html` to PDF in a loop (output discarded),
3. every `sampleEvery` conversions, forces GC and prints **used (post-GC) heap**,
4. prints a class histogram diff (baseline vs final) of the top retained-byte growers.

`-Xmx` is set to `2g` in `build.gradle` so the trend is easy to see; lower it to reach
`OutOfMemoryError` sooner, or raise it to run longer.

## Expected vs. actual

- **Expected:** because every `HTMLDocument` and `Configuration` is disposed, post-GC live heap
  should reach a steady state and stay flat across thousands of identical conversions.
- **Actual:** post-GC live heap rises linearly and never plateaus; the process trends toward
  `OutOfMemoryError`.

## Observed (500-conversion sample run, licensed, JDK 17)

```
conversions   liveHeapMB(post-GC)
          0         64.6
        100         82.0
        200         98.2
        300        113.4
        400        130.8
        500        145.9
delta = +81.3 MB over 500 conversions  (~167 KB/conversion, linear)
```

An extended run in our application (same dispose pattern, larger document) showed the same shape
over **3000 conversions**: 328 MB → 1148 MB, ~280 KB/conversion, **no plateau**. Metaspace /
compressed class space stay flat throughout, so this is heap object retention, not class-metadata
growth.

## Where the memory goes (class histogram diff, live objects)

```
     deltaKB     deltaInstances   class
     19968.2             851976   com.aspose.html.utils.aGE
     19968.2             851976   com.aspose.html.utils.aGE$b
      8812.3             281992   com.aspose.html.utils.ms.System.Drawing.Color
      6609.2             281992   com.aspose.html.utils.aGD
      6609.2             281992   com.aspose.html.utils.aKL
      6609.2             281992   com.aspose.html.utils.aJR
      3304.6              70498   com.aspose.html.utils.aKK
      2423.3             103394   com.aspose.html.utils.ms.System.Collections.Generic.Dictionary$Link
      2203.1              70498   com.aspose.html.utils.aLh
```

The growth set is essentially 100% `com.aspose.html.utils.*` (and the embedded
`System.Drawing.Color`). The instance counts scale linearly with the number of conversions —
i.e. a fixed number of objects is retained **per conversion** and never released.

## What we already tried

- Dispose `HTMLDocument` **and** `Configuration` every conversion (shown in
  `HtmlToPdfLeakRepro.convertOnce`) — as the docs require. No effect on the trend.
- A fresh `Configuration` per conversion (not a shared/static one).
- Forcing GC before each measurement (so this is retained, not uncollected, memory).

The only thing that fully reclaims the memory is tearing down and rebuilding the classloader that
loaded Aspose (or restarting the process), which discards these static caches — consistent with
the leak living in Aspose-internal `static` state.

## Questions for Aspose

1. Is this a known issue, and is there a fix targeted for an upcoming release?
2. Is there a supported API to **clear/flush these internal caches** between conversions in a
   long-running process (so we don't have to recycle the classloader/process)?
3. If not, what is the recommended pattern for high-volume, long-lived JVM services that must
   convert many documents without unbounded heap growth?

## Files

- `src/main/java/com/example/aspose/HtmlToPdfLeakRepro.java` — the harness (Aspose + JDK only)
- `src/main/resources/sample.html` — synthetic styled document (no proprietary content)
- `build.gradle` — single dependency: `com.aspose:aspose-html:26.5:jdk17` from Aspose's public repo
