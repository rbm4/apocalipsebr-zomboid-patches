package zombie.characters;
public final class Roles {
    public static final Role role=new Role();
    public static int initialized;
    public static void init() { initialized++; }
    public static Role getDefaultForAdmin() { return role; }
    public static Role getDefaultForNewUser() { return role; }
    public static Role getRoleById(int id) { return role; }
}
