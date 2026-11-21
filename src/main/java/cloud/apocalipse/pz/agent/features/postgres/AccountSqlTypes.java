package cloud.apocalipse.pz.agent.features.postgres;

import java.util.Locale;

/** Narrow fixes for numeric columns that vanilla SQLite binds using setString. */
final class AccountSqlTypes {
    private AccountSqlTypes() { }
    static String normalized(String sql) { return sql == null ? "" : sql.replaceAll("\\s+", "").toLowerCase(Locale.ROOT); }
    static boolean integerParameter(String sql, int index) {
        return (index == 2 && sql.equals("updatewhitelistsetusername=?whereid=?"))
            || (index == 1 && sql.startsWith("updateuserlogsetamount=?,lastupdate=?"))
            || (index == 5 && sql.equals("insertintouserlog(username,type,text,issuedby,amount,lastupdate)values(?,?,?,?,?,?)"));
    }
}
