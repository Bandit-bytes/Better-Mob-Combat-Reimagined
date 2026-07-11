package bmc_re.better_mob_combat.api;

public enum RangedWeaponKind {
    BOW,
    CROSSBOW,
    SPEAR,
    GENERIC;

    public static RangedWeaponKind byId(int id) {
        RangedWeaponKind[] values = values();
        return id >= 0 && id < values.length ? values[id] : GENERIC;
    }
}
