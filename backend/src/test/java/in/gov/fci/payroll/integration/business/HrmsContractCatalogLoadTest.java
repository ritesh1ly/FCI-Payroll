package in.gov.fci.payroll.integration.business;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SRS-INT-1: a contract file missing from the class path stops the load with a clear message. */
class HrmsContractCatalogLoadTest {

    @Test
    void SRS_INT_1_aMissingContractFileIsReportedByName() throws Exception {
        List<URL> urls = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            URL url = new File(entry).toURI().toURL();
            if (!url.toString().contains("/resources/main")) {
                urls.add(url);
            }
        }
        try (URLClassLoader isolated = new URLClassLoader(urls.toArray(new URL[0]), null)) {
            Class<?> catalog = isolated.loadClass(HrmsContractCatalog.class.getName());
            assertThat(isolated.getResource(HrmsContractCatalog.RESOURCE)).isNull();
            Throwable thrown =
                    org.assertj.core.api.Assertions.catchThrowable(
                            () -> {
                                try {
                                    catalog.getMethod("load").invoke(null);
                                } catch (InvocationTargetException e) {
                                    throw e.getCause();
                                }
                            });
            assertThat(thrown)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(HrmsContractCatalog.RESOURCE + " is missing from the class path");
        }
    }
}
