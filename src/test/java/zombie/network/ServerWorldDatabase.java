package zombie.network;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Structural account double. Never packaged; production transforms the original game class. */
public class ServerWorldDatabase {
    Connection conn;
    public String commandLineAdminUsername="admin";
    public String commandLineAdminPassword;
    public boolean doAdmin=true;
    public int originalCalls;

    public void create() { throw new AssertionError("SQLite create executed"); }
    public void connect() { throw new AssertionError("SQLite connect executed"); }
    public void close() { throw new AssertionError("Shared native connection close executed"); }
    public String getTOTPCode(String secret) { return "native-pure-method"; }
    public static String encrypt(String value) { return "fixture-encoded:"+value; }
    private boolean isNewAccountAllowed(String username,String ip,long steamId) {
        throw new AssertionError("Native SQLite account-limit branch executed");
    }
    public boolean canCreateForTest(String username,long steamId) { return isNewAccountAllowed(username,"127.0.0.1",steamId); }

    public boolean containsUser(String username) { return containsUser(username,"fixture-world"); }
    public boolean containsUser(String username,String world) {
        originalCalls++;
        if (conn == null) return false;
        try (PreparedStatement statement=conn.prepareStatement("SELECT id FROM whitelist WHERE username=? AND world=?")) {
            statement.setString(1,username); statement.setString(2,world);
            try (var rows=statement.executeQuery()) { return rows.next(); }
        } catch (SQLException caughtByVanilla) { return false; }
    }

    public LogonResult authClient(String username,String password,String ip,long steamId,int authType) {
        originalCalls++;
        LogonResult result=new LogonResult();
        try (PreparedStatement statement=conn.prepareStatement("SELECT role FROM whitelist WHERE username=?")) {
            statement.setString(1,username);
            try (var rows=statement.executeQuery()) { result.authorized=rows.next(); }
        } catch (SQLException caughtByVanilla) {
            result.authorized=true; // Regression trap: advice must deny even if vanilla swallows a SQL failure.
        }
        return result;
    }

    public String changeUsername(String username,String replacement) {
        originalCalls++;
        try (var statement=conn.prepareStatement("UPDATE whitelist SET username = ? WHERE id = ?")) {
            statement.setString(1,replacement); statement.setString(2,"17"); statement.executeUpdate();
        } catch (SQLException caughtByVanilla) { }
        return "native-body-completed";
    }

    public String addUser(String username,String password) {
        originalCalls++;
        try (var statement=conn.prepareStatement("INSERT INTO userlog (username, type, text, issuedBy, amount, lastUpdate) VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setString(1,username); statement.setString(5,"3"); statement.executeUpdate();
        } catch (SQLException caughtByVanilla) { }
        return "native-body-completed";
    }

    public class LogonResult {
        public boolean authorized,needSecondFactor;
        public String dcReason;
    }
}
