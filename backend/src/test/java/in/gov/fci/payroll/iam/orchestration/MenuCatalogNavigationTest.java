package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.iam.business.model.AppMenuGroup;
import in.gov.fci.payroll.iam.business.model.AppMenuItem;
import in.gov.fci.payroll.iam.business.model.AppScreen;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.platform.cache.CacheProperties;
import in.gov.fci.payroll.platform.cache.CacheRegions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * SRS-G-54 (OD-121: Wireframes v6.1 is a frozen requirement): the sidebar is the pack's navigation
 * as drawn - groups in the pack's order, {@code home} first and header-less, one entry per drawn
 * item with its own label key and view, so one screen appears under several groups; entries of
 * screens not yet in the registry are left out; a registry without menu rows falls back to the
 * screen modules (V033_1).
 */
class MenuCatalogNavigationTest {

    private final AccessRepositories.Screens screens = mock(AccessRepositories.Screens.class);
    private final AccessRepositories.MenuGroups groups = mock(AccessRepositories.MenuGroups.class);
    private final AccessRepositories.MenuItems items = mock(AccessRepositories.MenuItems.class);
    private final MenuCatalog catalog;

    MenuCatalogNavigationTest() {
        CacheRegions regions = mock(CacheRegions.class);
        CacheProperties cache = mock(CacheProperties.class);
        when(cache.maxAge()).thenReturn(Duration.ofMinutes(5));
        when(regions.properties()).thenReturn(cache);
        catalog = new MenuCatalog(screens, groups, items, regions);
    }

    // Mocks are built before any stubbing that uses them (Mockito refuses a stub inside a stub).
    private static AppScreen screen(String code, String module, String name) {
        AppScreen s = mock(AppScreen.class);
        when(s.getCode()).thenReturn(code);
        when(s.getModuleName()).thenReturn(module);
        when(s.getScreenName()).thenReturn(name);
        return s;
    }

    private static AppMenuGroup group(String code, int order) {
        AppMenuGroup g = mock(AppMenuGroup.class);
        when(g.getCode()).thenReturn(code);
        when(g.getGroupOrder()).thenReturn(order);
        return g;
    }

    private static AppMenuItem item(
            String id, String group, int order, String screen, String suffix) {
        AppMenuItem i = mock(AppMenuItem.class);
        when(i.getId()).thenReturn(id);
        when(i.getGroupCode()).thenReturn(group);
        when(i.getItemOrder()).thenReturn(order);
        when(i.getScreenCode()).thenReturn(screen);
        when(i.getRouteSuffix()).thenReturn(suffix);
        return i;
    }

    private void wireframeNavigation() {
        List<AppScreen> registry =
                List.of(
                        screen("D-01", "Calculation", "Home"),
                        screen("M-02", "Masters & Setup", "Labour Master"),
                        screen("I-01", "Provisions & Inputs", "Attendance"));
        List<AppMenuGroup> navigation =
                List.of(
                        group("home", 1),
                        group("departmental_labour", 2),
                        group("dps_labour", 3),
                        group("cpf_pf_vpf", 4));
        // The repository answers in (group, order) order: the catalogue keeps the group order of
        // the groups table, not of the items.
        List<AppMenuItem> entries =
                List.of(
                        item("i12", "cpf_pf_vpf", 1, "I-12", ""),
                        item("m2", "departmental_labour", 1, "M-02", "&engine=DEPT"),
                        item("i1", "departmental_labour", 2, "I-01", "&engine=DEPT"),
                        item("m2d", "dps_labour", 1, "M-02", "&engine=DPS"),
                        item("d1", "home", 1, "D-01", ""));
        when(screens.findAllByOrderByDisplayOrder()).thenReturn(registry);
        when(groups.findAllByOrderByGroupOrder()).thenReturn(navigation);
        when(items.findAllByOrderByGroupCodeAscItemOrderAsc()).thenReturn(entries);
    }

    @Test
    void SRS_G_54_theSidebarIsTheWireframeNavigationGroupsInOrderHomeFirst() {
        wireframeNavigation();

        List<MenuCatalog.Section> sections = catalog.sections();

        assertThat(sections)
                .extracting(MenuCatalog.Section::id)
                .containsExactly("home", "departmental_labour", "dps_labour");
        assertThat(sections.get(0).labelKey()).isEqualTo("menu.section.home");
        assertThat(sections.get(0).items())
                .containsExactly(
                        new MenuCatalog.Item(
                                "d1", "D-01", "nav.d1", "Home", "/payroll?s=D-01", "D-01:VIEW"));
    }

    @Test
    void SRS_G_54_aScreenDrawnUnderTwoGroupsHasOneEntryPerGroupWithItsOwnLabelAndView() {
        wireframeNavigation();

        List<MenuCatalog.Section> sections = catalog.sections();

        assertThat(sections.get(1).items())
                .containsExactly(
                        new MenuCatalog.Item(
                                "m2",
                                "M-02",
                                "nav.m2",
                                "Labour Master",
                                "/payroll?s=M-02&engine=DEPT",
                                "M-02:VIEW"),
                        new MenuCatalog.Item(
                                "i1",
                                "I-01",
                                "nav.i1",
                                "Attendance",
                                "/payroll?s=I-01&engine=DEPT",
                                "I-01:VIEW"));
        assertThat(sections.get(2).items())
                .containsExactly(
                        new MenuCatalog.Item(
                                "m2d",
                                "M-02",
                                "nav.m2d",
                                "Labour Master",
                                "/payroll?s=M-02&engine=DPS",
                                "M-02:VIEW"));
    }

    @Test
    void SRS_G_54_anEntryOfAScreenNotInTheRegistryIsLeftOutAndItsGroupDroppedWhenEmpty() {
        wireframeNavigation();

        List<MenuCatalog.Section> sections = catalog.sections();

        assertThat(sections).extracting(MenuCatalog.Section::id).doesNotContain("cpf_pf_vpf");
        assertThat(sections.stream().flatMap(s -> s.items().stream()).map(MenuCatalog.Item::code))
                .doesNotContain("I-12");
    }

    @Test
    void SRS_G_54_theFilteredMenuCountsEveryHiddenEntryAndKeepsBothEntriesOfAPermittedScreen() {
        wireframeNavigation();

        Map<String, Object> body = catalog.filtered(Set.of("M-02:VIEW"));

        @SuppressWarnings("unchecked")
        List<MenuCatalog.Section> visible = (List<MenuCatalog.Section>) body.get("sections");
        assertThat(visible)
                .extracting(MenuCatalog.Section::id)
                .containsExactly("departmental_labour", "dps_labour");
        assertThat(visible.get(0).items()).extracting(MenuCatalog.Item::id).containsExactly("m2");
        assertThat(visible.get(1).items()).extracting(MenuCatalog.Item::id).containsExactly("m2d");
        assertThat(body.get("hiddenCount")).isEqualTo(2);
    }

    @Test
    void SRS_G_54_aRegistryWithoutNavigationRowsFallsBackToTheScreenModules() {
        List<AppScreen> registry = List.of(screen("M-01", "Masters & Setup", "Employee Master"));
        when(groups.findAllByOrderByGroupOrder()).thenReturn(List.of());
        when(screens.findAllByOrderByDisplayOrder()).thenReturn(registry);

        List<MenuCatalog.Section> sections = catalog.sections();

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).id()).isEqualTo("masters_setup");
        assertThat(sections.get(0).items())
                .containsExactly(
                        new MenuCatalog.Item(
                                "M-01",
                                "M-01",
                                "screen.M-01",
                                "Employee Master",
                                "/payroll?s=M-01",
                                "M-01:VIEW"));
    }
}
