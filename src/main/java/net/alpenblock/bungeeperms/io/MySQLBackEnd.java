package net.alpenblock.bungeeperms.io;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.alpenblock.bungeeperms.BPConfig;
import net.alpenblock.bungeeperms.BungeePerms;
import net.alpenblock.bungeeperms.ChatColor;
import net.alpenblock.bungeeperms.Debug;
import net.alpenblock.bungeeperms.Group;
import net.alpenblock.bungeeperms.Mysql;
import net.alpenblock.bungeeperms.MysqlConfig;
import net.alpenblock.bungeeperms.Server;
import net.alpenblock.bungeeperms.Statics;
import net.alpenblock.bungeeperms.User;
import net.alpenblock.bungeeperms.World;
import net.alpenblock.bungeeperms.platform.PlatformPlugin;

public class MySQLBackEnd implements BackEnd
{

    private final PlatformPlugin plugin;
    private final BPConfig config;
    private final Debug debug;
    private final Mysql mysql;

    private MysqlConfig permsconf;
    private final String table;

    public MySQLBackEnd()
    {
        plugin = BungeePerms.getInstance().getPlugin();
        config = BungeePerms.getInstance().getConfig();
        debug = BungeePerms.getInstance().getDebug();

        mysql = new Mysql(config.getConfig(), debug, "bungeeperms");
        mysql.connect();

        table = config.getTablePrefix() + "permissions";

        permsconf = new MysqlConfig(mysql, table);
        permsconf.createTable();
    }

    @Override
    public BackEndType getType()
    {
        return BackEndType.MySQL;
    }

    @Override
    public void load()
    {
        BungeePerms.getInstance().getPlugin().getConsole().sendMessage(ChatColor.RED + "The MySQL backend is deprecated! Please consider to use MySQL2.");

        //load from table
        permsconf.load();
    }

    @Override
    public List<Group> loadGroups()
    {
        List<Group> ret = new ArrayList<>();

        List<String> groups = permsconf.getSubNodes("groups");
        for (String g : groups)
        {
            ret.add(loadGroup(g));
        }
        Collections.sort(ret);

        return ret;
    }

    @Override
    public List<User> loadUsers()
    {
        List<User> ret = new ArrayList<>();

        List<String> users = permsconf.getSubNodes("users");
        for (String u : users)
        {
            User user = BungeePerms.getInstance().getConfig().isUseUUIDs() ? loadUser(UUID.fromString(u)) : loadUser(u);
            ret.add(user);
        }

        return ret;
    }

    @Override
    public Group loadGroup(String group)
    {
        MysqlConfig permsconf = new MysqlConfig(mysql, table);

        PreparedStatement stmt = null;
        ResultSet res = null;
        try
        {
            mysql.checkConnection();
            stmt = mysql.stmt("SELECT `key`,`value` FROM `" + table + "` WHERE `key` LIKE ? ORDER BY id ASC");
            stmt.setString(1, "groups." + group + "%");
            res = mysql.returnQuery(stmt);
            permsconf.fromResult(res);
        }
        catch (Exception e)
        {
            debug.log(e);
        }
        finally
        {
            Mysql.close(res);
            Mysql.close(stmt);
        }

        if (!permsconf.keyExists("groups." + group))
        {
            return null;
        }

        List<String> inheritances = permsconf.getListString("groups." + group + ".inheritances", new ArrayList<String>());
        List<String> permissions = permsconf.getListString("groups." + group + ".permissions", new ArrayList<String>());
        boolean isdefault = permsconf.getBoolean("groups." + group + ".default", false);
        int rank = permsconf.getInt("groups." + group + ".rank", 1000);
        int weight = permsconf.getInt("groups." + group + ".weight", 1000);
        String ladder = permsconf.getString("groups." + group + ".ladder", "default");
        String display = permsconf.getString("groups." + group + ".display", null);
        String prefix = permsconf.getString("groups." + group + ".prefix", null);
        String suffix = permsconf.getString("groups." + group + ".suffix", null);

        //per server perms
        Map<String, Server> servers = new HashMap<>();
        for (String server : permsconf.getSubNodes("groups." + group + ".servers"))
        {
            List<String> serverperms = permsconf.getListString("groups." + group + ".servers." + server + ".permissions", new ArrayList<String>());
            String sdisplay = permsconf.getString("groups." + group + ".servers." + server + ".display", null);
            String sprefix = permsconf.getString("groups." + group + ".servers." + server + ".prefix", null);
            String ssuffix = permsconf.getString("groups." + group + ".servers." + server + ".suffix", null);

            //per server world perms
            Map<String, World> worlds = new HashMap<>();
            for (String world : permsconf.getSubNodes("groups." + group + ".servers." + server + ".worlds"))
            {
                List<String> worldperms = permsconf.getListString("groups." + group + ".servers." + server + ".worlds." + world + ".permissions", new ArrayList<String>());
                String wdisplay = permsconf.getString("groups." + group + ".servers." + server + ".worlds." + world + ".display", null);
                String wprefix = permsconf.getString("groups." + group + ".servers." + server + ".worlds." + world + ".prefix", null);
                String wsuffix = permsconf.getString("groups." + group + ".servers." + server + ".worlds." + world + ".suffix", null);

                World w = new World(Statics.toLower(world), worldperms, wdisplay, wprefix, wsuffix);
                worlds.put(Statics.toLower(world), w);
            }

            servers.put(Statics.toLower(server), new Server(Statics.toLower(server), serverperms, worlds, sdisplay, sprefix, ssuffix));
        }

        Group g = new Group(group, inheritances, permissions, servers, rank, weight, ladder, isdefault, display, prefix, suffix);

        return g;
    }

    @Override
    public User loadUser(String user)
    {
        if (!permsconf.keyExists("users." + user))
        {
            return null;
        }

        //load user from database
        List<String> sgroups = permsconf.getListString("users." + user + ".groups", new ArrayList<String>());
        List<String> perms = permsconf.getListString("users." + user + ".permissions", new ArrayList<String>());
        String display = permsconf.getString("users." + user + ".display", null);
        String prefix = permsconf.getString("users." + user + ".prefix", null);
        String suffix = permsconf.getString("users." + user + ".suffix", null);

        List<Group> lgroups = new ArrayList<>();
        for (String s : sgroups)
        {
            Group g = BungeePerms.getInstance().getPermissionsManager().getGroup(s);
            if (g != null)
            {
                lgroups.add(g);
            }
        }

        //per server perms
        Map<String, Server> servers = new HashMap<>();
        for (String server : permsconf.getSubNodes("users." + user + ".servers"))
        {
            List<String> serverperms = permsconf.getListString("users." + user + ".servers." + server + ".permissions", new ArrayList<String>());
            String sdisplay = permsconf.getString("users." + user + ".servers." + server + ".display", null);
            String sprefix = permsconf.getString("users." + user + ".servers." + server + ".prefix", null);
            String ssuffix = permsconf.getString("users." + user + ".servers." + server + ".suffix", null);

            //per server world perms
            Map<String, World> worlds = new HashMap<>();
            for (String world : permsconf.getSubNodes("users." + user + ".servers." + server + ".worlds"))
            {
                List<String> worldperms = permsconf.getListString("users." + user + ".servers." + server + ".worlds." + world + ".permissions", new ArrayList<String>());
                String wdisplay = permsconf.getString("users." + user + ".servers." + server + ".worlds." + world + ".display", null);
                String wprefix = permsconf.getString("users." + user + ".servers." + server + ".worlds." + world + ".prefix", null);
                String wsuffix = permsconf.getString("users." + user + ".servers." + server + ".worlds." + world + ".suffix", null);

                World w = new World(Statics.toLower(world), worldperms, wdisplay, wprefix, wsuffix);
                worlds.put(Statics.toLower(world), w);
            }

            servers.put(Statics.toLower(server), new Server(Statics.toLower(server), serverperms, worlds, sdisplay, sprefix, ssuffix));
        }

        UUID uuid = BungeePerms.getInstance().getPermissionsManager().getUUIDPlayerDB().getUUID(user);
        User u = new User(user, uuid, lgroups, perms, servers, display, prefix, suffix);
        return u;
    }

    @Override
    public User loadUser(UUID user)
    {
        if (!permsconf.keyExists("users." + user))
        {
            return null;
        }

        //load user from database
        List<String> sgroups = permsconf.getListString("users." + user + ".groups", new ArrayList<String>());
        List<String> perms = permsconf.getListString("users." + user + ".permissions", new ArrayList<String>());
        String display = permsconf.getString("users." + user + ".display", null);
        String prefix = permsconf.getString("users." + user + ".prefix", null);
        String suffix = permsconf.getString("users." + user + ".suffix", null);

        List<Group> lgroups = new ArrayList<>();
        for (String s : sgroups)
        {
            Group g = BungeePerms.getInstance().getPermissionsManager().getGroup(s);
            if (g != null)
            {
                lgroups.add(g);
            }
        }

        //per server perms
        Map<String, Server> servers = new HashMap<>();
        for (String server : permsconf.getSubNodes("users." + user + ".servers"))
        {
            List<String> serverperms = permsconf.getListString("users." + user + ".servers." + server + ".permissions", new ArrayList<String>());
            String sdisplay = permsconf.getString("users." + user + ".servers." + server + ".display", null);
            String sprefix = permsconf.getString("users." + user + ".servers." + server + ".prefix", null);
            String ssuffix = permsconf.getString("users." + user + ".servers." + server + ".suffix", null);

            //per server world perms
            Map<String, World> worlds = new HashMap<>();
            for (String world : permsconf.getSubNodes("users." + user + ".servers." + server + ".worlds"))
            {
                List<String> worldperms = permsconf.getListString("users." + user + ".servers." + server + ".worlds." + world + ".permissions", new ArrayList<String>());
                String wdisplay = permsconf.getString("users." + user + ".servers." + server + ".worlds." + world + ".display", null);
                String wprefix = permsconf.getString("users." + user + ".servers." + server + ".worlds." + world + ".prefix", null);
                String wsuffix = permsconf.getString("users." + user + ".servers." + server + ".worlds." + world + ".suffix", null);

                World w = new World(Statics.toLower(world), worldperms, wdisplay, wprefix, wsuffix);
                worlds.put(Statics.toLower(world), w);
            }

            servers.put(Statics.toLower(server), new Server(Statics.toLower(server), serverperms, worlds, sdisplay, sprefix, ssuffix));
        }

        String username = BungeePerms.getInstance().getPermissionsManager().getUUIDPlayerDB().getPlayerName(user);
        User u = new User(username, user, lgroups, perms, servers, display, prefix, suffix);
        return u;
    }

    @Override
    public int loadVersion()
    {
        return permsconf.getInt("version", 1);
    }

    @Override
    public void saveVersion(int version, boolean savetodisk)
    {
        permsconf.setInt("version", version);
    }

    @Override
    public boolean isUserInDatabase(User user)
    {
        return permsconf.keyExists("users." + (BungeePerms.getInstance().getConfig().isUseUUIDs() ? user.getUUID().toString() : user.getName()));
    }

    @Override
    public List<String> getRegisteredUsers()
    {
        return permsconf.getSubNodes("users");
    }

    @Override
    public List<String> getGroupUsers(Group group)
    {
        List<String> users = new ArrayList<>();

        for (String user : permsconf.getSubNodes("users"))
        {
            if (permsconf.getListString("users." + user + ".groups", new ArrayList<String>()).contains(group.getName()))
            {
                users.add(user);
            }
        }

        return users;
    }

    @Override
    public synchronized void saveUser(User user, boolean savetodisk)
    {
        if (BungeePerms.getInstance().getConfig().isSaveAllUsers() ? true : !user.isNothingSpecial())
        {
            List<String> groups = new ArrayList<>();
            for (Group g : user.getGroups())
            {
                groups.add(g.getName());
            }

            String uname = BungeePerms.getInstance().getConfig().isUseUUIDs() ? user.getUUID().toString() : user.getName();

            permsconf.setListString("users." + uname + ".groups", groups);
            permsconf.setListString("users." + uname + ".permissions", user.getExtraPerms());

            for (Map.Entry<String, Server> se : user.getServers().entrySet())
            {
                permsconf.setListString("users." + uname + ".servers." + se.getKey() + ".permissions", se.getValue().getPerms());
                permsconf.setString("users." + uname + ".servers." + se.getKey() + ".display", se.getValue().getDisplay());
                permsconf.setString("users." + uname + ".servers." + se.getKey() + ".prefix", se.getValue().getPrefix());
                permsconf.setString("users." + uname + ".servers." + se.getKey() + ".suffix", se.getValue().getSuffix());

                for (Map.Entry<String, World> we : se.getValue().getWorlds().entrySet())
                {
                    permsconf.setListString("users." + uname + ".servers." + se.getKey() + ".worlds." + we.getKey() + ".permissions", we.getValue().getPerms());
                    permsconf.setString("users." + uname + ".servers." + se.getKey() + ".worlds." + we.getKey() + ".display", we.getValue().getDisplay());
                    permsconf.setString("users." + uname + ".servers." + se.getKey() + ".worlds." + we.getKey() + ".prefix", we.getValue().getPrefix());
                    permsconf.setString("users." + uname + ".servers." + se.getKey() + ".worlds." + we.getKey() + ".suffix", we.getValue().getSuffix());
                }
            }
        }
    }

    @Override
    public synchronized void saveGroup(Group group, boolean savetodisk)
    {
        permsconf.setListString("groups." + group.getName() + ".inheritances", group.getInheritances());
        permsconf.setListString("groups." + group.getName() + ".permissions", group.getPerms());
        permsconf.setInt("groups." + group.getName() + ".rank", group.getRank());
        permsconf.setString("groups." + group.getName() + ".ladder", group.getLadder());
        permsconf.setBool("groups." + group.getName() + ".default", group.isDefault());
        permsconf.setString("groups." + group.getName() + ".display", group.getDisplay());
        permsconf.setString("groups." + group.getName() + ".prefix", group.getPrefix());
        permsconf.setString("groups." + group.getName() + ".suffix", group.getSuffix());

        for (Map.Entry<String, Server> se : group.getServers().entrySet())
        {
            permsconf.setListString("groups." + group.getName() + ".servers." + se.getKey() + ".permissions", se.getValue().getPerms());
            permsconf.setString("groups." + group.getName() + ".servers." + se.getKey() + ".display", se.getValue().getDisplay());
            permsconf.setString("groups." + group.getName() + ".servers." + se.getKey() + ".prefix", se.getValue().getPrefix());
            permsconf.setString("groups." + group.getName() + ".servers." + se.getKey() + ".suffix", se.getValue().getSuffix());

            for (Map.Entry<String, World> we : se.getValue().getWorlds().entrySet())
            {
                permsconf.setListString("groups." + group.getName() + ".servers." + se.getKey() + ".worlds." + we.getKey() + ".permissions", we.getValue().getPerms());
                permsconf.setString("groups." + group.getName() + ".servers." + se.getKey() + ".worlds." + we.getKey() + ".display", we.getValue().getDisplay());
                permsconf.setString("groups." + group.getName() + ".servers." + se.getKey() + ".worlds." + we.getKey() + ".prefix", we.getValue().getPrefix());
                permsconf.setString("groups." + group.getName() + ".servers." + se.getKey() + ".worlds." + we.getKey() + ".suffix", we.getValue().getSuffix());
            }
        }
    }

    @Override
    public synchronized void deleteUser(User user)
    {
        permsconf.deleteNode("users." + (BungeePerms.getInstance().getConfig().isUseUUIDs() ? user.getUUID().toString() : user.getName()));
    }

    @Override
    public synchronized void deleteGroup(Group group)
    {
        permsconf.deleteNode("groups." + group.getName());
    }

    @Override
    public synchronized void saveUserGroups(User user)
    {
        List<String> savegroups = new ArrayList<>();
        for (Group g : user.getGroups())
        {
            savegroups.add(g.getName());
        }

        permsconf.setListString("users." + (BungeePerms.getInstance().getConfig().isUseUUIDs() ? user.getUUID().toString() : user.getName()) + ".groups", savegroups);
    }

    @Override
    public synchronized void saveUserPerms(User user)
    {
        permsconf.setListString("users." + (BungeePerms.getInstance().getConfig().isUseUUIDs() ? user.getUUID().toString() : user.getName()) + ".permissions", user.getExtraPerms());
    }

    @Override
    public synchronized void saveUserPerServerPerms(User user, String server)
    {
        server = Statics.toLower(server);

        permsconf.setListString("users." + (BungeePerms.getInstance().getConfig().isUseUUIDs() ? user.getUUID().toString() : user.getName()) + ".servers." + server + ".permissions", user.getServer(server).getPerms());
    }

    @Override
    public synchronized void saveUserPerServerWorldPerms(User user, String server, String world)
    {
        server = Statics.toLower(server);
        world = Statics.toLower(world);

        permsconf.setListString("users." + (BungeePerms.getInstance().getConfig().isUseUUIDs() ? user.getUUID().toString() : user.getName()) + ".servers." + server + ".worlds." + world + ".permissions", user.getServer(server).getWorld(world).getPerms());
    }

    @Override
    public synchronized void saveUserDisplay(User user, String server, String world)
    {
        server = Statics.toLower(server);
        world = Statics.toLower(world);

        String display = user.getDisplay();
        if (server != null)
        {
            display = user.getServer(server).getDisplay();
            if (world != null)
            {
                display = user.getServer(server).getWorld(world).getDisplay();
            }
        }
        permsconf.setString("users." + (BungeePerms.getInstance().getConfig().isUseUUIDs() ? user.getUUID().toString() : user.getName()) + (server != null ? ".servers." + server + (world != null ? ".worlds." + world : "") : "") + ".display", display);
    }

    @Override
    public synchronized void saveUserPrefix(User user, String server, String world)
    {
        server = Statics.toLower(server);
        world = Statics.toLower(world);

        String prefix = user.getPrefix();
        if (server != null)
        {
            prefix = user.getServer(server).getPrefix();
            if (world != null)
            {
                prefix = user.getServer(server).getWorld(world).getPrefix();
            }
        }
        permsconf.setString("users." + (BungeePerms.getInstance().getConfig().isUseUUIDs() ? user.getUUID().toString() : user.getName()) + (server != null ? ".servers." + server + (world != null ? ".worlds." + world : "") : "") + ".prefix", prefix);
    }

    @Override
    public synchronized void saveUserSuffix(User user, String server, String world)
    {
        server = Statics.toLower(server);
        world = Statics.toLower(world);

        String suffix = user.getSuffix();
        if (server != null)
        {
            suffix = user.getServer(server).getSuffix();
            if (world != null)
            {
                suffix = user.getServer(server).getWorld(world).getSuffix();
            }
        }
        permsconf.setString("users." + (BungeePerms.getInstance().getConfig().isUseUUIDs() ? user.getUUID().toString() : user.getName()) + (server != null ? ".servers." + server + (world != null ? ".worlds." + world : "") : "") + ".suffix", suffix);
    }

    @Override
    public synchronized void saveGroupPerms(Group group)
    {
        permsconf.setListString("groups." + group.getName() + ".permissions", group.getPerms());
    }

    @Override
    public synchronized void saveGroupPerServerPerms(Group group, String server)
    {
        server = Statics.toLower(server);

        permsconf.setListString("groups." + group.getName() + ".servers." + server + ".permissions", group.getServer(server).getPerms());
    }

    @Override
    public synchronized void saveGroupPerServerWorldPerms(Group group, String server, String world)
    {
        server = Statics.toLower(server);
        world = Statics.toLower(world);

        permsconf.setListString("groups." + group.getName() + ".servers." + server + ".worlds." + world + ".permissions", group.getServer(server).getWorld(world).getPerms());
    }

    @Override
    public synchronized void saveGroupInheritances(Group group)
    {
        permsconf.setListString("groups." + group.getName() + ".inheritances", group.getInheritances());
    }

    @Override
    public synchronized void saveGroupLadder(Group group)
    {
        permsconf.setString("groups." + group.getName() + ".ladder", group.getLadder());
    }

    @Override
    public synchronized void saveGroupRank(Group group)
    {
        permsconf.setInt("groups." + group.getName() + ".rank", group.getRank());
    }

    @Override
    public synchronized void saveGroupWeight(Group group)
    {
        permsconf.setInt("groups." + group.getName() + ".weight", group.getWeight());
    }

    @Override
    public synchronized void saveGroupDefault(Group group)
    {
        permsconf.setBool("groups." + group.getName() + ".default", group.isDefault());
    }

    @Override
    public synchronized void saveGroupDisplay(Group group, String server, String world)
    {
        server = Statics.toLower(server);
        world = Statics.toLower(world);

        String display = group.getDisplay();
        if (server != null)
        {
            display = group.getServer(server).getDisplay();
            if (world != null)
            {
                display = group.getServer(server).getWorld(world).getDisplay();
            }
        }
        permsconf.setString("groups." + group.getName() + (server != null ? ".servers." + server + (world != null ? ".worlds." + world : "") : "") + ".display", display);
    }

    @Override
    public synchronized void saveGroupPrefix(Group group, String server, String world)
    {
        server = Statics.toLower(server);
        world = Statics.toLower(world);

        String prefix = group.getPrefix();
        if (server != null)
        {
            prefix = group.getServer(server).getPrefix();
            if (world != null)
            {
                prefix = group.getServer(server).getWorld(world).getPrefix();
            }
        }
        permsconf.setString("groups." + group.getName() + (server != null ? ".servers." + server + (world != null ? ".worlds." + world : "") : "") + ".prefix", prefix);
    }

    @Override
    public synchronized void saveGroupSuffix(Group group, String server, String world)
    {
        server = Statics.toLower(server);
        world = Statics.toLower(world);

        String suffix = group.getSuffix();
        if (server != null)
        {
            suffix = group.getServer(server).getSuffix();
            if (world != null)
            {
                suffix = group.getServer(server).getWorld(world).getSuffix();
            }
        }
        permsconf.setString("groups." + group.getName() + (server != null ? ".servers." + server + (world != null ? ".worlds." + world : "") : "") + ".suffix", suffix);
    }

    @Override
    public synchronized void format(List<Group> groups, List<User> users, int version)
    {
        clearDatabase();
        for (int i = 0; i < groups.size(); i++)
        {
            saveGroup(groups.get(i), false);
        }
        for (int i = 0; i < users.size(); i++)
        {
            saveUser(users.get(i), false);
        }
        saveVersion(version, false);
    }

    @Override
    public synchronized int cleanup(List<Group> groups, List<User> users, int version)
    {
        int deleted = 0;

        clearDatabase();
        for (int i = 0; i < groups.size(); i++)
        {
            saveGroup(groups.get(i), false);
        }
        for (int i = 0; i < users.size(); i++)
        {
            User u = users.get(i);
            if (BungeePerms.getInstance().getConfig().isDeleteUsersOnCleanup())
            {
                //check for additional permissions and non-default groups AND onlinecheck
                if (u.isNothingSpecial()
                    && plugin.getPlayer(u.getName()) == null
                    && plugin.getPlayer(u.getUUID()) == null)
                {
                    deleted++;
                    continue;
                }
            }

            //player has to be saved
            saveUser(users.get(i), false);
        }
        saveVersion(version, false);

        return deleted;
    }

    @Override
    public void clearDatabase()
    {
        permsconf.clearTable(table);
        permsconf = new MysqlConfig(mysql, table);
        load();
    }

    @Override
    public void reloadGroup(Group group)
    {
        MysqlConfig permsconf = new MysqlConfig(mysql, table);

        PreparedStatement stmt = null;
        ResultSet res = null;
        try
        {
            mysql.checkConnection();
            stmt = mysql.stmt("SELECT `key`,`value` FROM `" + table + "` WHERE `key` LIKE ? ORDER BY id ASC");
            stmt.setString(1, "groups." + group.getName() + "%");
            res = mysql.returnQuery(stmt);
            permsconf.fromResult(res);
        }
        catch (Exception e)
        {
            debug.log(e);
        }
        finally
        {
            Mysql.close(res);
            Mysql.close(stmt);
        }

        //load group from database
        List<String> inheritances = permsconf.getListString("groups." + group.getName() + ".inheritances", new ArrayList<String>());
        List<String> permissions = permsconf.getListString("groups." + group.getName() + ".permissions", new ArrayList<String>());
        boolean isdefault = permsconf.getBoolean("groups." + group.getName() + ".default", false);
        int rank = permsconf.getInt("groups." + group.getName() + ".rank", 1000);
        int weight = permsconf.getInt("groups." + group.getName() + ".weight", 1000);
        String ladder = permsconf.getString("groups." + group.getName() + ".ladder", "default");
        String display = permsconf.getString("groups." + group.getName() + ".display", null);
        String prefix = permsconf.getString("groups." + group.getName() + ".prefix", null);
        String suffix = permsconf.getString("groups." + group.getName() + ".suffix", null);

        //per server perms
        Map<String, Server> servers = new HashMap<>();
        for (String server : permsconf.getSubNodes("groups." + group.getName() + ".servers"))
        {
            List<String> serverperms = permsconf.getListString("groups." + group.getName() + ".servers." + server + ".permissions", new ArrayList<String>());
            String sdisplay = permsconf.getString("groups." + group.getName() + ".servers." + server + ".display", null);
            String sprefix = permsconf.getString("groups." + group.getName() + ".servers." + server + ".prefix", null);
            String ssuffix = permsconf.getString("groups." + group.getName() + ".servers." + server + ".suffix", null);

            //per server world perms
            Map<String, World> worlds = new HashMap<>();
            for (String world : permsconf.getSubNodes("groups." + group.getName() + ".servers." + server + ".worlds"))
            {
                List<String> worldperms = permsconf.getListString("groups." + group.getName() + ".servers." + server + ".worlds." + world + ".permissions", new ArrayList<String>());
                String wdisplay = permsconf.getString("groups." + group.getName() + ".servers." + server + ".worlds." + world + ".display", null);
                String wprefix = permsconf.getString("groups." + group.getName() + ".servers." + server + ".worlds." + world + ".prefix", null);
                String wsuffix = permsconf.getString("groups." + group.getName() + ".servers." + server + ".worlds." + world + ".suffix", null);

                World w = new World(Statics.toLower(world), worldperms, wdisplay, wprefix, wsuffix);
                worlds.put(Statics.toLower(world), w);
            }

            servers.put(Statics.toLower(server), new Server(Statics.toLower(server), serverperms, worlds, sdisplay, sprefix, ssuffix));
        }

        group.setInheritances(inheritances);
        group.setPerms(permissions);
        group.setIsdefault(isdefault);
        group.setRank(rank);
        group.setWeight(weight);
        group.setLadder(ladder);
        group.setDisplay(display);
        group.setPrefix(prefix);
        group.setSuffix(suffix);
        group.setServers(servers);
    }

    @Override
    public void reloadUser(User user)
    {
        MysqlConfig permsconf = new MysqlConfig(mysql, table);

        PreparedStatement stmt = null;
        ResultSet res = null;
        try
        {
            mysql.checkConnection();
            stmt = mysql.stmt("SELECT `key`,`value` FROM `" + table + "` WHERE `key` LIKE ? ORDER BY id ASC");
            stmt.setString(1, "users." + (config.isUseUUIDs() ? user.getUUID().toString() : user.getName()) + "%");
            res = mysql.returnQuery(stmt);
            permsconf.fromResult(res);
        }
        catch (Exception e)
        {
            debug.log(e);
        }
        finally
        {
            Mysql.close(res);
            Mysql.close(stmt);
        }

        String uname = config.isUseUUIDs() ? user.getUUID().toString() : user.getName();

        //load user from database
        List<String> sgroups = permsconf.getListString("users." + uname + ".groups", new ArrayList<String>());
        List<String> globalperms = permsconf.getListString("users." + uname + ".permissions", new ArrayList<String>());
        String display = permsconf.getString("users." + uname + ".display", null);
        String prefix = permsconf.getString("users." + uname + ".prefix", null);
        String suffix = permsconf.getString("users." + uname + ".suffix", null);

        List<Group> lgroups = new ArrayList<>();
        for (String s : sgroups)
        {
            Group g = BungeePerms.getInstance().getPermissionsManager().getGroup(s);
            if (g != null)
            {
                lgroups.add(g);
            }
        }

        //per server perms
        Map<String, Server> servers = new HashMap<>();
        for (String server : permsconf.getSubNodes("users." + uname + ".servers"))
        {
            List<String> serverperms = permsconf.getListString("users." + uname + ".servers." + server + ".permissions", new ArrayList<String>());
            String sdisplay = permsconf.getString("users." + uname + ".servers." + server + ".display", null);
            String sprefix = permsconf.getString("users." + uname + ".servers." + server + ".prefix", null);
            String ssuffix = permsconf.getString("users." + uname + ".servers." + server + ".suffix", null);

            //per server world perms
            Map<String, World> worlds = new HashMap<>();
            for (String world : permsconf.getSubNodes("users." + uname + ".servers." + server + ".worlds"))
            {
                List<String> worldperms = permsconf.getListString("users." + uname + ".servers." + server + ".worlds." + world + ".permissions", new ArrayList<String>());
                String wdisplay = permsconf.getString("users." + uname + ".servers." + server + ".worlds." + world + ".display", null);
                String wprefix = permsconf.getString("users." + uname + ".servers." + server + ".worlds." + world + ".prefix", null);
                String wsuffix = permsconf.getString("users." + uname + ".servers." + server + ".worlds." + world + ".suffix", null);

                World w = new World(Statics.toLower(world), worldperms, wdisplay, wprefix, wsuffix);
                worlds.put(Statics.toLower(world), w);
            }

            servers.put(Statics.toLower(server), new Server(Statics.toLower(server), serverperms, worlds, sdisplay, sprefix, ssuffix));
        }

        user.setGroups(lgroups);
        user.setExtraPerms(globalperms);
        user.setDisplay(display);
        user.setPrefix(prefix);
        user.setSuffix(suffix);
        user.setServers(servers);
    }
}
