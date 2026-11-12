# ApocalipseBR 42.22.2 client telemetry

Main-thread measurement scaffold for connected multiplayer gameplay. Disabled by
default; compilation and deployment tested in disposable installations, not live-game verified.

- [Documentation index](docs/README.md)
- [Entry point, metrics and startup properties](docs/features/2026-11-12-client-main-thread-telemetry.md)
- [Baseline provenance and validation](docs/reports/2026-11-12-client-telemetry-validation.md)

Install from the repository root (or double-click instalador.bat with the game closed):

```powershell
.\patch-client.ps1
```

Installation compiles into a temporary directory, prepares a separate
projectzomboid-apocbr.jar, deploys loose zombie classes and redirects native
launcher JSON to that JAR. The original projectzomboid.jar remains untouched.
Use -DryRun to validate/compile without installation, -Revert to restore previous
loose classes and original JAR references, or -BuildOnly for retained artifacts:

```powershell
.\patch-client.ps1 -DryRun
.\patch-client.ps1 -BuildOnly
.\patch-client.ps1 -Revert
py -3.12 .\42.22.2-client\tests\test_client_telemetry.py
py -3.12 .\42.22.2-client\tests\test_client_deployment.py
```

Once installed through the client override deployment flow, enable at JVM startup
with `-Dapocbr.clientTelemetry.enabled=true`. Output defaults to
`apocbr-client-telemetry.ndjson` in the process working directory.
