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

### Gatling Load Test (Production Build)

Gatling wurde zusätzlich gegen den Production-Build auf `http://127.0.0.1:5174/game` ausgeführt.

| Kennzahl | Wert |
| :--- | :---: |
| **Users** | 50 |
| **Requests** | 50 |
| **Failures** | 0 |
| **Mean Response Time** | 8 ms |
| **p95 Response Time** | 15 ms |
| **Max Response Time** | 19 ms |
| **Assertions** | p99 < 500 ms, Failures < 1% |

---

## Verbesserungen nach Refactoring (Status: Optimiert)

Nach der Implementierung von Hot-Path-Optimierungen in `LegalMoveGenerator`, `Board`, `CheckValidator` und `GameStatusEvaluator` wurden folgende Verbesserungen gemessen:

### JMH Allocation Metriken

| Metrik | Baseline | Optimiert (Dev) | Verbesserung |
| :--- | :---: | :---: | :---: |
| **GC Allocation/Op** | ~17 KB | ~7.1 KB | **-58.8%** ✅ |
| **GC Alloc Rate** | ~600 MB/sec | ~252 MB/sec | **-58%** ✅ |

### k6 Stress Test — Dev Server vs Production Build (50 VU)

| Metrik | Dev Server (Port 5173) | Production Build (Port 5174) | Verbesserung |
| :--- | :---: | :---: | :---: |
| **Total Requests** | 3,315 | 17,042 | **+414%** ✅ |
| **Avg Latency** | — | 17.2 ms | — |
| **p95 Latency** | 971.48 ms | 45.1 ms | **-95.4%** ✅ |
| **Max Latency** | 5.31 s | 70.81 ms | **-98.7%** ✅ |
| **Error Rate** | 0.00% | 0.00% | ✅ Keine Regression |

### k6 Scalability Test — Dev Server vs Production Build

| VU-Count | Dev Server (req/s) | Production (req/s) | Dev p95 (ms) | Prod p95 (ms) | Latenz-Verbesserung |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **1 VU** | 9.33 | 9.71 | 11.47 | 3.18 | **-72.3%** ✅ |
| **10 VUs** | 69.88 | 95.29 | 62.34 | 7.92 | **-87.3%** ✅ |
| **50 VUs** | 180.11 | 410.90 | 244.57 | 46.74 | **-80.9%** ✅ |

### Refactoring-Maßnahmen (Item B)

Neben der LegalMoveGenerator-Optimierung wurden folgende Board-Methoden hinzugefügt:
1. **Board.movePiece()** - Kombiniert remove+place in einer Map-Operation
2. **Board.moveTwoPieces()** - Optimiert Castling mit zwei atomaren Moves
3. **Board.moveAndRemove()** - Optimiert En Passant (move + remove)
4. **MoveApplier Update** - Verwendet `movePiece()` statt `remove().place()`
5. **CastlingApplier Update** - Verwendet `moveTwoPieces()` für beide König+Turm
6. **EnPassantApplier Update** - Verwendet `moveAndRemove()` für Bauer+Capture

### Final JMH Results (mit Board-Optimierungen)

Nach Implementierung der Board-Methoden-Optimierungen (movePiece, moveTwoPieces, moveAndRemove):

| Benchmark | Throughput | Allocation (B/op) | Allocation (KB/op) |
| :--- | ---: | ---: | ---: |
| **benchmarkLegalMovesFromE2Pawn** | 319,127.852 ops/s | 7,240.022 | **7.24 KB** |
| **benchmarkLegalMovesFromG1Knight** | 285,634.333 ops/s | 7,272.024 | **7.27 KB** |
| **benchmarkLegalTargetsFromE2Pawn** | 349,992.551 ops/s | 6,896.020 | **6.90 KB** |

**Fazit:** Board-Optimierungen halten Allocation stabil auf **~7.0-7.3 KB/op**. Keine weitere Reduktion durch die neuen Methoden, aber die Implementierung verhindert Regression und ist architektonisch sauberer.

### Interpretation

**Dev-Server vs Production Build:**
- Der Dev-Server hat **massive Cold-Start-Effekte** (Vite On-the-fly Transpilierung)
- p95 Latenz-Reduktion von **71-87%** beim Wechsel zu Production Build
- Max Latenz-Spike von **5.31s → 70.81ms** zeigt den Dev-Server Overhead
- **Throughput-Skalierung ist linear** bis 50 VU: 9.71 → 410.9 req/s

**Refactoring-Effekte:**
- GC Allocation: 58.8% Reduktion durch Piece-Specific Candidate Generation
- Production Build demonstriert die **wahre Leistung** ohne Dev-Server Overhead
- System ist stabil unter allen Last-Profilen (0% Error Rate konsistent)
- Board-Optimierungen stabilisieren Allocation ohne Regression
