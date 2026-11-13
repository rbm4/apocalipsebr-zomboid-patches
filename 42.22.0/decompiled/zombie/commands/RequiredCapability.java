// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.commands;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import zombie.characters.Capability;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(RequiredCapabilities.class)
public @interface RequiredCapability {
    Capability requiredCapability();

    String argName() default "NO_ARGUMENT_NAME";
}
