# Test results

How to run everything:

```
gradlew.bat :core:test
```

## 2026-10-14 Milestone 1: core engine

`gradlew :core:test`: **36 tests, 0 failures** (BUILD SUCCESSFUL, about 16 s).

Engine tests run against a real local HTTP server (`TestServer`) that supports byte ranges,
throttling, dropped connections, redirects, Content-Disposition and servers without range support.

| Suite | Tests | What is covered |
| --- | --- | --- |
| EngineTest | 17 | 8-connection download is byte-identical; no-range server; unknown size; pause/resume; resume after app restart; retry after dropped connections; 404 message; redirect + Content-Disposition name; user name kept and duplicate renamed; checksum pass/fail; global speed limit; concurrency limit; queue run + finished event; remove + redownload; category folders; HLS AES-128 decrypt and join; probe; events |
| FileNamesTest | 5 | Content-Disposition (quoted, plain, RFC 5987), URL names, sanitising, MIME extensions, unique names |
| SpeedTest | 3 | Token bucket rate, unlimited mode, speed/ETA meter |
| BatchUrlsTest | 3 | `[001-100]` and `[a-z]` expansion, link extraction |
| SchedulerTest | 2 | Start/stop window once per day, weekdays |
| Others | 6 | Categories, segment planner, checksums, formatting, JSON store with corrupt-file fallback, HLS parser |
