# ApocalipseBR 42.22.2 client telemetry

Main-thread measurement scaffold for connected multiplayer gameplay. Disabled by
default; compiled and tested in isolation, not deployed or live-game verified.

- [Documentation index](docs/README.md)
- [Entry point, metrics and startup properties](docs/features/2026-11-12-client-main-thread-telemetry.md)
- [Baseline provenance and validation](docs/reports/2026-11-12-client-telemetry-validation.md)

Build from the repository root (build only, no game changes):

```powershell
.\42.22.2-client\Build-ClientTelemetry.ps1
py -3.12 .\42.22.2-client\tests\test_client_telemetry.py
```

Once installed through the client override deployment flow, enable at JVM startup
with `-Dapocbr.clientTelemetry.enabled=true`. Output defaults to
`apocbr-client-telemetry.ndjson` in the process working directory.
