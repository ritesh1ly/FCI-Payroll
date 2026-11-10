package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
 * Mutation gaps of {@link MenuCatalog}: the menu shows only screens whose {@code :VIEW} permission
 * the session holds, counts the hidden ones, drops empty sections and keeps the registry order (HLD
 * v0.6 navigation groups, SRS-RBAC menu filtering).
 */
class MenuCatalogMutationGapsTest {

    private final AccessRepositories.Screens screens = mock(AccessRepositories.Screens.class);
    private final MenuCatalog catalog;

    MenuCatalogMutationGapsTest() {
        CacheRegions regions = mock(CacheRegions.class);
        CacheProperties cache = mock(CacheProperties.class);
        when(cache.maxAge()).thenReturn(Duration.ofMinutes(5));
        when(regions.properties()).thenReturn(cache);
        // No navigation rows (V078): the fallback by screen module (V033_1) under test here.
        AccessRepositories.MenuGroups groups = mock(AccessRepositories.MenuGroups.class);
        when(groups.findAllByOrderByGroupOrder()).thenReturn(List.of());
        catalog =
                new MenuCatalog(screens, groups, mock(AccessRepositories.MenuItems.class), regions);
    }

    private static AppScreen screen(String code, String module, String name) {
        AppScreen s = mock(AppScreen.class);
        when(s.getCode()).thenReturn(code);
        when(s.getModuleName()).thenReturn(module);
        when(s.getScreenName()).thenReturn(name);
        return s;
    }

    private void registry() {
        AppScreen s1 = screen("M-01", "Masters & Setup", "Employee Master");
        AppScreen s2 = screen("P-02", "Provisions & Inputs", "Attendance");
        AppScreen s3 = screen("M-02", "Masters & Setup", "Office Master");
        when(screens.findAllByOrderByDisplayOrder()).thenReturn(List.of(s1, s2, s3));
    }

    @Test
    void sectionsGroupScreensByModuleInOrderOfTheirFirstScreenWithIdAndKeys() {
        registry();

        List<MenuCatalog.Section> sections = catalog.sections();

        assertThat(sections).hasSize(2);
        MenuCatalog.Section first = sections.get(0);
        assertThat(first.id()).isEqualTo("masters_setup");
        assertThat(first.labelKey()).isEqualTo("menu.section.masters_setup");
        assertThat(first.label()).isEqualTo("Masters & Setup");
        assertThat(first.items())
                .containsExactly(
                        new MenuCatalog.Item(
                                "M-01",
                                "screen.M-01",
                                "Employee Master",
                                "/payroll?s=M-01",
                                "M-01:VIEW"),
                        new MenuCatalog.Item(
                                "M-02",
                                "screen.M-02",
                                "Office Master",
                                "/payroll?s=M-02",
                                "M-02:VIEW"));
        assertThat(sections.get(1).id()).isEqualTo("provisions_inputs");
        assertThat(sections.get(1).items()).hasSize(1);
    }

    @Test
    void sectionsOfAnEmptyRegistryAreEmpty() {
        when(screens.findAllByOrderByDisplayOrder()).thenReturn(List.of());
        assertThat(catalog.sections()).isEmpty();
    }

    @Test
    void filteredKeepsOnlyPermittedItemsCountsHiddenAndDropsEmptySections() {
        registry();

        Map<String, Object> body = catalog.filtered(Set.of("P-02:VIEW", "M-02:VIEW"));

        @SuppressWarnings("unchecked")
        List<MenuCatalog.Section> visible = (List<MenuCatalog.Section>) body.get("sections");
        assertThat(visible).hasSize(2);
        assertThat(visible.get(0).id()).isEqualTo("masters_setup");
        assertThat(visible.get(0).items())
                .extracting(MenuCatalog.Item::code)
                .containsExactly("M-02");
        assertThat(visible.get(1).items())
                .extracting(MenuCatalog.Item::code)
                .containsExactly("P-02");
        assertThat(body.get("hiddenCount")).isEqualTo(1);
    }

    @Test
    void filteredWithNoPermissionShowsNothingAndCountsEveryScreenHidden() {
        registry();

        Map<String, Object> body = catalog.filtered(Set.of("M-01:EDIT"));

        assertThat((List<?>) body.get("sections")).isEmpty();
        assertThat(body.get("hiddenCount")).isEqualTo(3);
    }

    @Test
    void filteredWithAllPermissionsHidesNothing() {
        registry();

        Map<String, Object> body = catalog.filtered(Set.of("M-01:VIEW", "M-02:VIEW", "P-02:VIEW"));

        assertThat((List<?>) body.get("sections")).hasSize(2);
        assertThat(body.get("hiddenCount")).isEqualTo(0);
    }
}
