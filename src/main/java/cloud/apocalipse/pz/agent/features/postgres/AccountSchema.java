package cloud.apocalipse.pz.agent.features.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Backend-owned schema contract. Engine validation never creates or alters PostgreSQL tables. */
public final class AccountSchema {
    private static final String[] QUERIES = {
        "SELECT id,world,username,password,lastconnection,role,authtype,googlekey,steamid,ownerid,displayname FROM public.whitelist WHERE false",
        "SELECT steamid FROM public.allowedsteamid WHERE false",
        "SELECT steamid,reason FROM public.bannedid WHERE false",
        "SELECT ip,username,reason FROM public.bannedip WHERE false",
        "SELECT id,name,description,colorr,colorg,colorb,readonly,position FROM public.role WHERE false",
        "SELECT name,role FROM public.capabilities WHERE false",
        "SELECT name,role FROM public.defaultroles WHERE false",
        "SELECT id,username,type,text,issuedby,amount,lastupdate FROM public.userlog WHERE false",
        "SELECT id,message,author,answeredid,viewed FROM public.tickets WHERE false"
    };

    private AccountSchema() { }

    public static void validate(Connection connection) throws SQLException {
        for (String query : QUERIES) {
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setQueryTimeout(2);
                try (ResultSet ignored = statement.executeQuery()) { }
            } catch (SQLException failure) {
                String table = query.substring(query.indexOf(" FROM ") + 6, query.indexOf(" WHERE "));
                SQLException safe = PostgresErrors.sanitized("schema contract " + table, failure);
                throw safe;
            }
        }
    }
}
