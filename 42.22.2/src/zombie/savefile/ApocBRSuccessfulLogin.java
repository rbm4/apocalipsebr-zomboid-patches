package zombie.savefile;

import java.sql.*;
import java.util.UUID;

/** Detached successful connection audit. Executed only by durable journal replay. */
final class ApocBRSuccessfulLogin {
    record Login(UUID event,long loggedAt,int server,String world,String identity,boolean coop,
                 int slot,String username,String steam,UUID character) implements ApocBRPersistenceJournal.Operation {
        Login {
            if(event==null||loggedAt<=0||server<1||world==null||world.isBlank()||identity==null||identity.isBlank()
                    ||slot<0||slot>3||username==null||username.isBlank())throw new IllegalArgumentException("Invalid login audit");
        }
        public String kind(){return "successful-login-v1";}
        public long estimatedBytes(){return 1024L+2L*(world.length()+identity.length()+username.length()+(steam==null?0:steam.length()));}
    }
    static final String SQL="SELECT player_rel.record_successful_login(?,?,?,?,?,?,?,?,?,?)";
    static void apply(Login login)throws SQLException {
        int previous;
        try(Connection c=ApocBRPostgresConnector.borrowPlayerConnection()) {
            try {
                // The event UUID makes retries safe even after a lost COMMIT response.
                previous=record(c,login);
                c.commit();
            }catch(SQLException|RuntimeException failure){try{c.rollback();}catch(SQLException ignored){}throw failure;}
        }
        ApocBRPlayerLogin.recorded(login.event(),previous);
    }
    static int record(Connection c,Login login)throws SQLException {
        try(PreparedStatement s=c.prepareStatement(SQL)) {
            s.setQueryTimeout(2);s.setObject(1,login.event());s.setTimestamp(2,new Timestamp(login.loggedAt()));
            s.setInt(3,login.server());s.setString(4,login.world());s.setString(5,login.coop()?"steam_coop":"username");
            s.setString(6,login.identity());s.setInt(7,login.slot());s.setString(8,login.username());
            s.setString(9,login.steam());s.setObject(10,login.character());
            try(ResultSet r=s.executeQuery()){if(!r.next())throw new SQLException("Missing login decision","02000");return r.getInt(1);}
        }
    }
    private ApocBRSuccessfulLogin(){}
}
