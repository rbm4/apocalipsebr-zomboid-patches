# Server hotfixes

The `hotfixes` feature package contains bounded compatibility repairs for the
original 42.22.2 game JAR. Hotfixes are independently registered in
`AgentModules` and remain reversible through explicit JVM properties.

## XML case-insensitive asset lookup

`XmlCaseModule` instruments `zombie.util.PZXmlUtil.parseXmlInternal(String)`,
the original method that opens a resolved XML filename for parsing. It mutates
the filename argument before vanilla `FileInputStream` opens the file.

The lookup keeps exact paths unchanged. If the path is missing, it resolves each
path component case-insensitively only when exactly one filesystem entry matches.
Missing, unreadable and ambiguous paths are preserved so vanilla reports the
original error.

Enabled by default. Disable with:

```text
-Dapocbr.hotfixes.xmlCaseInsensitive.enabled=false
```

The legacy 42.22.2 patch also implements this behavior in
`PZXmlUtil`; the agent port supplies the same intent through Byte Buddy rather
than replacing or compiling the vanilla class.
