// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.worldMap.network;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Map.Entry;
import zombie.Lua.LuaManager;
import zombie.core.logger.ExceptionLogger;

public final class HiddenAuthors {
    private static final String FILE_NAME = "hidden_authors.ini";
    private static final HashMap<String, HiddenAuthors.ServerData> serverData = new HashMap<>();

    public static void read() {
        serverData.clear();

        try {
            try (BufferedReader input = LuaManager.GlobalObject.getFileReader("hidden_authors.ini", false)) {
                if (input != null) {
                    while (true) {
                        String serverName = input.readLine();
                        if (serverName == null || serverName.isEmpty()) {
                            return;
                        }

                        String userNames = input.readLine();
                        if (userNames == null) {
                            return;
                        }

                        HiddenAuthors.ServerData serverData1 = new HiddenAuthors.ServerData();
                        serverData1.serverName = serverName;
                        String[] userNamesArray = userNames.split(",");

                        for (String userName : userNamesArray) {
                            String userName1 = userName.replaceAll("&comma;", ",");
                            if (!userName1.isEmpty()) {
                                serverData1.hidden.add(userName1);
                            }
                        }

                        serverData.put(serverName, serverData1);
                    }
                }
            }
        } catch (IOException var12) {
            ExceptionLogger.logException(var12);
        }
    }

    public static void write() {
        String fileName = LuaManager.getLuaCacheDir() + File.separator + "hidden_authors.ini";

        try (
            FileOutputStream fos = new FileOutputStream(fileName, false);
            OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
        ) {
            for (Entry<String, HiddenAuthors.ServerData> it : serverData.entrySet()) {
                osw.write(it.getKey());
                osw.append(System.lineSeparator());
                StringBuilder sb = new StringBuilder();

                for (String userName : it.getValue().hidden) {
                    String userName2 = userName.replaceAll(",", "&comma;");
                    sb.append(userName2);
                    sb.append(",");
                }

                osw.write(sb.toString());
                osw.append(System.lineSeparator());
            }
        } catch (IOException var13) {
            ExceptionLogger.logException(var13);
        }
    }

    public static void setAuthorHidden(String serverName, String userName, boolean hidden) {
        HiddenAuthors.ServerData serverData1 = serverData.computeIfAbsent(serverName, k -> new HiddenAuthors.ServerData());
        if (hidden) {
            serverData1.hidden.add(userName);
        } else {
            serverData1.hidden.remove(userName);
        }
    }

    public static boolean isAuthorHidden(String serverName, String userName) {
        HiddenAuthors.ServerData serverData1 = serverData.get(serverName);
        return serverData1 != null && serverData1.hidden.contains(userName);
    }

    private static final class ServerData {
        String serverName;
        final Set<String> hidden = new HashSet<>();
    }
}
