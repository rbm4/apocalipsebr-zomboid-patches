package zombie.savefile;

import java.lang.reflect.*;
import java.sql.*;
import java.util.concurrent.TimeUnit;

/** One deadline shared by nested calls in a packet/API workflow. */
public final class ApocBRForegroundDatabase implements AutoCloseable {
    private static final ThreadLocal<Long> deadline=new ThreadLocal<>();
    private final Long previous;
    private ApocBRForegroundDatabase(){previous=deadline.get();if(previous==null)deadline.set(System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(500));}
    public static ApocBRForegroundDatabase begin(){return new ApocBRForegroundDatabase();}
    static int acquisitionMillis()throws SQLException {return deadline.get()==null?250:remaining(deadline.get());}
    @Override public void close(){if(previous==null)deadline.remove();else deadline.set(previous);}
    static Connection wrap(Connection connection)throws SQLException {
        long expires=deadline.get()==null?System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(250):deadline.get();
        return proxy(Connection.class,connection,connection,expires);
    }
    private static int remaining(long expires)throws SQLException {
        long left=expires-System.nanoTime();if(left<=0)throw new SQLTransientConnectionException("Foreground database deadline exceeded","08001");
        return (int)Math.max(1,Math.min(250,TimeUnit.NANOSECONDS.toMillis(left)));
    }
    @SuppressWarnings("unchecked")
    private static <T>T proxy(Class<T> type,Object target,Connection connection,long expires) {
        return (T)Proxy.newProxyInstance(ApocBRForegroundDatabase.class.getClassLoader(),new Class<?>[]{type},(p,m,args)->{
            try {
                // Cleanup must work even after the deadline. Object methods must not make network calls.
                if(!m.getName().equals("close")&&!m.getName().equals("isClosed")&&m.getDeclaringClass()!=Object.class)
                    connection.setNetworkTimeout(Runnable::run,remaining(expires));
                Object result=m.invoke(target,args);
                if(result instanceof PreparedStatement s)return proxy(PreparedStatement.class,s,connection,expires);
                if(result instanceof Statement s)return proxy(Statement.class,s,connection,expires);
                if(result instanceof ResultSet s)return proxy(ResultSet.class,s,connection,expires);
                return result;
            }catch(InvocationTargetException wrapper){Throwable cause=wrapper.getCause();if(cause instanceof SQLException sql){ApocBRPersistence.failed(sql);throw new SQLException("PostgreSQL foreground operation unavailable",sql.getSQLState());}throw cause;}
            catch(SQLException error){ApocBRPersistence.failed(error);throw error;}
        });
    }
}
