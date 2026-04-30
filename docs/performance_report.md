# Performance Validation Report

Basierend auf den erweiterten Tests (k6 Stress- und Skalierbarkeitstests) sowie den JMH-Profiler-Ergebnissen ergibt sich folgendes Bild für die 4 angefragten Performance-Säulen:

| Säule | Status | Messwert | Empfehlung |
| :--- | :--- | :--- | :--- |
| **Responsiveness (Latency)** | ⚠️ Ausreißer vorhanden | **p95 Latency:** ~931 ms<br>**Max Spike:** ~3.95 s | Der 2-4s Spike entsteht durch den **Cold Start** des Vite Dev Servers (On-the-fly Transpilierung). Das System ist nach dem Aufwärmen responsiv (p95 bei ~113 ms für 1 VU). **Empfehlung:** Für verlässliche Latenz-Benchmarks immer gegen einen optimierten Production-Build testen, nicht gegen den Dev Server. |
| **Stability (Error Behavior)** | ✅ Sehr Stabil | **Error Rate:** 0.00 %<br>(0 Fehler bei 5238 Requests) | Der Soak/Stress Test in k6 (Ramp-up auf 50 VUs und Halten der Last) zeigte keinerlei Timeouts oder HTTP 5xx Fehler. Das System geht bei hoher Last nicht in die Knie. **Empfehlung:** Momentan kein Handlungsbedarf; das System degradiert graceful (Latenz steigt, aber keine Ausfälle). |
| **Scalability** | ⚠️ Diminishing Returns | **1 VU:** 6.7 req/s<br>**10 VUs:** 52.7 req/s<br>**50 VUs:** 138.3 req/s | Der Durchsatz skaliert von 1 auf 10 VUs nahezu linear (~8x), flacht aber bei 50 VUs deutlich ab (nur noch ~2.6x anstatt 5x). **Empfehlung:** Analyse der Backend-Ressourcen (Thread-Pools, Datenbank-Connections), da das System bei ca. 140 req/s in eine Sättigung läuft. |
| **Resource Usage** | ❌ Hoher GC Druck | **GC Alloc Rate:** ~600 MB/sec<br>**Alloc/Op:** ~17 KB pro Move-Gen | JMH Profile zeigen, dass die Move-Generation für Bauer (Pawn) und Springer (Knight) **unverhältnismäßig viel Speicher** alloziiert (~17 KB pro Operation). Dies führt unter Last zu massivem Garbage Collection (GC) Druck. **Empfehlung:** Refactoring des `LegalMoveGenerator`. Vermeidung von Objekt-Allokationen (z.B. Immutable Collections) in der inneren Schleife durch Primitives oder Arrays. |

> [!NOTE]
> Die k6 Lasttests wurden um die Skripte `stress_test.js` und `scalability_test.js` erweitert. JMH wurde mit dem `-prof gc` Flag zur Analyse der Speicherallokation ausgeführt.
