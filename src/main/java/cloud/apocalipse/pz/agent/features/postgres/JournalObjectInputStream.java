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
        "zombie.savefile.ApocBRPersistenceOperations$Sql", PersistenceOperations.Sql.class,
        "zombie.savefile.ApocBRHumanSales$Sale", cloud.apocalipse.pz.agent.features.npc.persistence.ApocBRHumanSales.Sale.class
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
        if(replacement==cloud.apocalipse.pz.agent.features.npc.persistence.ApocBRHumanSales.Sale.class){
            // Same simple record name after package relocation: resolveClass can retain Java's
            // record field evolution, including old receipts with no bundleItemIds component.
            if(old.getSerialVersionUID()!=current.getSerialVersionUID())throw new InvalidClassException("Legacy sale version incompatible");
            var fields=new java.util.HashMap<String,java.io.ObjectStreamField>();
            for(var field:after)fields.put(field.getName(),field);
            for(var field:before){
                var expected=fields.remove(field.getName());
                if(expected==null||expected.getTypeCode()!=field.getTypeCode()||!java.util.Objects.equals(expected.getTypeString(),field.getTypeString()))
                    throw new InvalidClassException("Legacy sale field incompatible");
            }
            if(!fields.isEmpty()&&!(fields.size()==1&&fields.containsKey("bundleItemIds")))throw new InvalidClassException("Legacy sale fields missing");
            return old;
        }
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

    @Override protected Class<?> resolveClass(ObjectStreamClass descriptor)throws IOException,ClassNotFoundException {
        Class<?> alias=ALIASES.get(descriptor.getName());return alias==null?super.resolveClass(descriptor):alias;
    }

    private static String normalized(String descriptor) {
        if ("Lzombie/savefile/ApocBRPersistenceJournal$Operation;".equals(descriptor)) {
            return "Lcloud/apocalipse/pz/agent/features/postgres/PersistenceJournal$Operation;";
        }
        return descriptor;
    }

    static boolean allowed(Class<?> type) {
        return type == PersistenceJournal.Entry.class || type == PersistenceOperations.Account.class
            || type == PersistenceOperations.Sql.class || type == SuccessfulLoginJournal.Login.class
            || type == cloud.apocalipse.pz.agent.features.npc.persistence.ApocBRHumanSales.Sale.class;
    }
}
