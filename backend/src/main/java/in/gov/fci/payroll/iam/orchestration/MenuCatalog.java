package in.gov.fci.payroll.iam.orchestration;

import in.gov.fci.payroll.iam.business.model.AppMenuGroup;
import in.gov.fci.payroll.iam.business.model.AppMenuItem;
import in.gov.fci.payroll.iam.business.model.AppScreen;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.platform.cache.CacheRegions;
import in.gov.fci.payroll.platform.cache.VersionedCache;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The navigation of the workspace. Since V078 (OD-121: Wireframes v6.1 is a frozen requirement,
 * SRS-G-54 screen designs) it is the pack's navigation as drawn: {@code app_menu_group} holds the
 * groups in order ({@code home} first, header-less), {@code app_menu_item} the entries of each
 * group with their own label key ({@code nav.<id>}) and view ({@code route_suffix}), so one screen
 * may appear under several groups (the labour masters per engine, the loans view of I-04). A
 * registry without menu rows (older schemas, unit tests) falls back to the screen registry {@code
 * app_screen} grouped by {@code module_name} in display order (V033_1). An item is shown only if
 * the session holds its {@code <screen>:VIEW} permission; the menu is a convenience, never the
 * protection: the page guard and every API call check the permission again. A screen added to
 * {@code app_screen} with its menu item appears without a release: the registry is cached per
 * instance (cache region MENU) and reloaded when the region is marked changed or after {@code
 * fci.cache.max-age}.
 */
@Component
public class MenuCatalog {

    /**
     * One menu entry. {@code id} is the wireframe's screen id ({@code m2d}), unique per entry even
     * when a screen appears twice; {@code requires} is a {@code SCREEN:ACTION} key; {@code label}
     * the registry name.
     */
    public record Item(
            String id, String code, String labelKey, String label, String route, String requires) {
        /** An entry of the fallback (one per screen, labelled by the screen). */
        public Item(String code, String labelKey, String label, String route, String requires) {
            this(code, code, labelKey, label, route, requires);
        }
    }

    public record Section(String id, String labelKey, String label, List<Item> items) {}

    /** The header-less first group of the pack (D-01 Home). */
    public static final String HOME = "home";

    private final AccessRepositories.Screens screens;
    private final AccessRepositories.MenuGroups groups;
    private final AccessRepositories.MenuItems items;

    public static final String REGION = "MENU";

    private final VersionedCache<String, List<Section>> cache;

    public MenuCatalog(
            AccessRepositories.Screens screens,
            AccessRepositories.MenuGroups groups,
            AccessRepositories.MenuItems items,
            CacheRegions regions) {
        this.screens = screens;
        this.groups = groups;
        this.items = items;
        this.cache = VersionedCache.of(REGION, regions, 1);
    }

    /** Sections with only the items the permissions allow; empty sections are dropped. */
    @Transactional(readOnly = true)
    public Map<String, Object> filtered(Set<String> permissions) {
        List<Section> visible = new ArrayList<>();
        int hidden = 0;
        for (Section section : sections()) {
            List<Item> allowed = new ArrayList<>();
            for (Item item : section.items()) {
                if (permissions.contains(item.requires())) {
                    allowed.add(item);
                } else {
                    hidden++;
                }
            }
            if (!allowed.isEmpty()) {
                visible.add(
                        new Section(section.id(), section.labelKey(), section.label(), allowed));
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sections", visible);
        body.put("hiddenCount", hidden);
        return body;
    }

    /** Every section of the registry in navigation order (cached). */
    @Transactional(readOnly = true)
    public List<Section> sections() {
        return cache.get("menu", k -> load());
    }

    private List<Section> load() {
        List<AppMenuGroup> navigation = groups.findAllByOrderByGroupOrder();
        return navigation.isEmpty() ? fromScreens() : fromNavigation(navigation);
    }

    /** The wireframe navigation: groups in order, each entry labelled and routed on its own. */
    private List<Section> fromNavigation(List<AppMenuGroup> navigation) {
        Map<String, AppScreen> registry = new LinkedHashMap<>();
        for (AppScreen screen : screens.findAllByOrderByDisplayOrder()) {
            registry.put(screen.getCode(), screen);
        }
        Map<String, List<Item>> byGroup = new LinkedHashMap<>();
        for (AppMenuItem entry : items.findAllByOrderByGroupCodeAscItemOrderAsc()) {
            AppScreen screen = registry.get(entry.getScreenCode());
            if (screen == null) {
                // A menu row of a screen not yet in the registry: nothing to show, nothing to hide.
                continue;
            }
            String code = screen.getCode();
            byGroup.computeIfAbsent(entry.getGroupCode(), k -> new ArrayList<>())
                    .add(
                            new Item(
                                    entry.getId(),
                                    code,
                                    "nav." + entry.getId(),
                                    screen.getScreenName(),
                                    "/payroll?s=" + code + entry.getRouteSuffix(),
                                    code + ":VIEW"));
        }
        List<Section> result = new ArrayList<>();
        for (AppMenuGroup group : navigation) {
            List<Item> entries = byGroup.getOrDefault(group.getCode(), List.of());
            if (!entries.isEmpty()) {
                result.add(
                        new Section(
                                group.getCode(),
                                "menu.section." + group.getCode(),
                                group.getCode(),
                                List.copyOf(entries)));
            }
        }
        return List.copyOf(result);
    }

    /** The fallback: one section per screen-registry module, in display order (V033_1). */
    private List<Section> fromScreens() {
        Map<String, List<Item>> byModule = new LinkedHashMap<>();
        for (AppScreen screen : screens.findAllByOrderByDisplayOrder()) {
            String code = screen.getCode();
            byModule.computeIfAbsent(screen.getModuleName(), k -> new ArrayList<>())
                    .add(
                            new Item(
                                    code,
                                    "screen." + code,
                                    screen.getScreenName(),
                                    "/payroll?s=" + code,
                                    code + ":VIEW"));
        }
        List<Section> result = new ArrayList<>();
        byModule.forEach(
                (module, entries) -> {
                    String id = module.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
                    result.add(new Section(id, "menu.section." + id, module, List.copyOf(entries)));
                });
        return List.copyOf(result);
    }
}
