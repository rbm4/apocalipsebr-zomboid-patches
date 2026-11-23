package cloud.apocalipse.pz.agent.features.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Scanner;

/** Only the legacy PostgreSQL bootstrap delta; all role/account APIs remain the instrumented native methods. */
final class AccountBootstrap {
    private AccountBootstrap() { }

    static void create(Object owner,Connection connection) throws SQLException {
        AccountSchema.validate(connection);
        try {
            Class<?> database=owner.getClass();
            ClassLoader loader=database.getClassLoader();
            String username=(String)database.getField("commandLineAdminUsername").get(owner);
            String suppliedPassword=(String)database.getField("commandLineAdminPassword").get(owner);
            boolean exists;
            try (PreparedStatement statement=connection.prepareStatement("SELECT id FROM whitelist WHERE username=?")) {
                statement.setString(1,username);
                try (ResultSet rows=statement.executeQuery()) { exists=rows.next(); }
            }
            if (!exists) {
                String password=suppliedPassword;
                if (password == null || password.isEmpty()) password=readPassword();
                PostgresRuntime.invokeStatic(loader,"zombie.characters.Roles","init",new Class<?>[0]);
                boolean admin=database.getField("doAdmin").getBoolean(owner);
                Object role=PostgresRuntime.invokeStatic(loader,"zombie.characters.Roles",
                    admin?"getDefaultForAdmin":"getDefaultForNewUser",new Class<?>[0]);
                int roleId=((Number)role.getClass().getMethod("getId").invoke(role)).intValue();
                String world=(String)PostgresRuntime.type(loader,"zombie.network.GameServer").getField("serverName").get(null);
                try (PreparedStatement statement=connection.prepareStatement(
                        "INSERT INTO whitelist (world,username,password,role) VALUES (?,?,?,?)")) {
                    statement.setString(1,world); statement.setString(2,username);
                    statement.setString(3,hash(database,loader,password)); statement.setInt(4,roleId);
                    statement.executeUpdate();
                }
            }
            if (suppliedPassword != null && !suppliedPassword.isEmpty()
                    && !PostgresRuntime.type(loader,"zombie.network.GameServer").getField("softReset").getBoolean(null)) {
                Integer id=null;
                try (PreparedStatement statement=connection.prepareStatement("SELECT id FROM whitelist WHERE username=?")) {
                    statement.setString(1,username);
                    try (ResultSet rows=statement.executeQuery()) { if (rows.next()) id=rows.getInt("id"); }
                }
                if (id != null) {
                    try (PreparedStatement statement=connection.prepareStatement("UPDATE whitelist SET password=? WHERE id=?")) {
                        statement.setString(1,hash(database,loader,suppliedPassword)); statement.setInt(2,id);
                        statement.executeUpdate();
                    }
                }
            }
        } catch (ReflectiveOperationException incompatible) {
            throw new IllegalStateException("PostgreSQL account bootstrap contract is incompatible");
        }
    }

    private static String hash(Class<?> database,ClassLoader loader,String password) throws ReflectiveOperationException {
        String encrypted=(String)database.getMethod("encrypt",String.class).invoke(null,password);
        return (String)PostgresRuntime.invokeStatic(loader,"zombie.core.secure.PZcrypt","hash",new Class<?>[]{String.class},encrypted);
    }

    private static String readPassword() {
        Scanner input=new Scanner(System.in);
        for (;;) {
            System.out.println("Enter new administrator password:");
            String password=input.nextLine();
            if (password.isEmpty()) continue;
            System.out.println("Confirm administrator password:");
            if (password.equals(input.nextLine())) return password;
            System.out.println("Passwords do not match.");
        }
    }
}
