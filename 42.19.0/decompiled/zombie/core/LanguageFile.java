// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import zombie.core.math.PZMath;
import zombie.scripting.ScriptParser;
import zombie.util.StringUtils;

public final class LanguageFile {
    private static final int VERSION1 = 1;
    private static final int VERSION = 1;
    public String name;
    public String text;
    public String base;
    public boolean azerty;

    public LanguageFile(String name, Path path) throws IOException {
        this.name = name;
        String contents = Files.readString(path);
        contents = ScriptParser.stripComments(contents);
        ScriptParser.Block block = ScriptParser.parse(contents);
        int version = -1;
        ScriptParser.Value value = block.getValue("VERSION");
        if (value != null) {
            version = PZMath.tryParseInt(value.getValue(), -1);
        }

        if (version >= 1 && version <= 1) {
            ScriptParser.Value text = block.getValue("text");
            if (text != null && !StringUtils.isNullOrWhitespace(text.getValue())) {
                this.text = text.getValue().trim();
                ScriptParser.Value base = block.getValue("base");
                if (base != null && !StringUtils.isNullOrWhitespace(base.getValue())) {
                    this.base = base.getValue().trim();
                }

                ScriptParser.Value azerty = block.getValue("azerty");
                if (azerty != null) {
                    this.azerty = StringUtils.tryParseBoolean(azerty.getValue());
                }
            } else {
                throw new RuntimeException("missing or empty value \"text\"");
            }
        } else {
            throw new RuntimeException("invalid or missing VERSION");
        }
    }
}
