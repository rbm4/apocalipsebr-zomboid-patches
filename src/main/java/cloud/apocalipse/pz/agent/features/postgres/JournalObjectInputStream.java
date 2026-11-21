package cloud.apocalipse.pz.agent.features.postgres;

import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.util.Map;

/** Explicit old-name aliases only; unknown legacy operations fail without acknowledging journal entries. */
final class JournalObjectInputStream extends ObjectInputStream {
    private static final Map<String, Class<?>> ALIASES = Map.of(
        "zombie.savefile.ApocBRPersistenceJournal$Entry", PersistenceJournal.Entry.class,
        "zombie.savefile.ApocBRPersistenceOperations$Account", PersistenceOperations.Account.class,
        "zombie.savefile.ApocBRPersistenceOperations$Sql", PersistenceOperations.Sql.class
    );

    JournalObjectInputStream(InputStream input) throws IOException { super(input); }

    @Override
    protected ObjectStreamClass readClassDescriptor() throws IOException, ClassNotFoundException {
        ObjectStreamClass old = super.readClassDescriptor();
        Class<?> replacement = ALIASES.get(old.getName());
        if (replacement == null) {
            if (old.getName().startsWith("zombie.savefile.ApocBR")) {
                throw new InvalidClassException("Legacy journal operation has not been migrated");
            }
            return old;
        }
        ObjectStreamClass current = ObjectStreamClass.lookup(replacement);
        var before = old.getFields();
        var after = current.getFields();
        if (old.getSerialVersionUID() != current.getSerialVersionUID() || before.length != after.length) {
            throw new InvalidClassException("Legacy journal descriptor is incompatible");
        }
        for (int i = 0; i < before.length; i++) {
            if (!before[i].getName().equals(after[i].getName()) || before[i].getTypeCode() != after[i].getTypeCode()) {
                throw new InvalidClassException("Legacy journal field layout is incompatible");
            }
            String oldType = normalized(before[i].getTypeString());
            if (!java.util.Objects.equals(oldType, after[i].getTypeString())) {
                throw new InvalidClassException("Legacy journal field type is incompatible");
            }
        }
        return current;
    }

    private static String normalized(String descriptor) {
        if ("Lzombie/savefile/ApocBRPersistenceJournal$Operation;".equals(descriptor)) {
            return "Lcloud/apocalipse/pz/agent/features/postgres/PersistenceJournal$Operation;";
        }
        return descriptor;
    }

    static boolean allowed(Class<?> type) {
        return type == PersistenceJournal.Entry.class || type == PersistenceOperations.Account.class
            || type == PersistenceOperations.Sql.class;
    }
}
