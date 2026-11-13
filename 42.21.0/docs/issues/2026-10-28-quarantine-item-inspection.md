# Inspect quarantined player items (42.21.0 server)

Status: diagnostic tool added and successfully used on the production record.
New capture support is implemented in the
[SpriteConfig persistence addition](../features/2026-10-28-player-sprite-config-persistence.md);
deployment remains pending. The companion
[discard-and-reload implementation](../features/2026-10-28-player-save-discard-recovery.md)
retires old player markers on startup without replaying their archived saves.
See [runtime failure isolation](../features/2026-10-17-persistence-runtime.md).

Use [InspectQuarantine.java](../../tools/InspectQuarantine.java) with Java 25 and
the matching deployed loose classes and game libraries. Copy a completed `.record`
to a temporary directory first. No server restart is required. The tool validates
the AP01 header, length and CRC32C, uses the journal deserialization allowlist,
and prints operation metadata and inventory items without database access or
journal changes. Treat output as administrative data: it includes player identity.

Linux example, after copying the tool to the current directory:

```bash
PZ='/absolute/path/to/ProjectZomboid'
RECORD='/absolute/path/to/postgres-journal/quarantine/79ecc031-2c3e-48a4-b5ae-367c14323a42.record'
COPY=$(mktemp /tmp/apocbr-quarantine.XXXXXX.record)
cp -- "$RECORD" "$COPY"
"$PZ/jre64/bin/java" -cp "$PZ/java:$PZ/java/*:$PZ:$PZ/*" \
  ./InspectQuarantine.java "$COPY"
```

If the bundled Java executable is elsewhere, use the same Java 25 executable as
the server. Include any additional loose-class or dependency directories from
the actual server classpath if they differ from this repository's deployment.
`ClassNotFoundException`, `NoClassDefFoundError`, or `InvalidClassException` means
the inspection environment/classes must be corrected; it does not establish
record corruption.

`SPRITECONFIG_ITEM` identifies each item carrying the component. `ITEM` lines list
the rest of the owned tree, including bag contents and weapon attachments. Multiple
items may carry SpriteConfig; the existing graphIssueCount is not a unique-item
count. The tool does not execute graph projection or reproduce other failure modes.
It does not decode `.pending` markers or repair/replay quarantined records.

Validation: Java 25 source launcher compilation and usage handling passed locally.
Inspection of an actual production record remains pending; none was provided.
