package in.gov.fci.payroll.integration.web;

import in.gov.fci.payroll.integration.api.HrmsApi;
import java.lang.reflect.Method;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Publishes each configured HRMS API at the path of its URL setting (SRS-INT-1, SRS-INT-16): at
 * start-up one request mapping per API in {@link HrmsApiRoutes}, all pointing at {@link
 * HrmsApiHandler#handle}. An API with no path has no mapping, so no handler runs for it. Method per
 * the collection: POST {@code request-*}, PUT {@code cancel-*}, GET {@code get-*}.
 */
@Component
@Profile("online")
public class HrmsApiRegistrar implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(HrmsApiRegistrar.class);

    private final HrmsApiRoutes routes;
    private final HrmsApiHandler handler;
    private final RequestMappingHandlerMapping mapping;

    public HrmsApiRegistrar(
            HrmsApiRoutes routes,
            HrmsApiHandler handler,
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mapping) {
        this.routes = routes;
        this.handler = handler;
        this.mapping = mapping;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Method target;
        try {
            target =
                    HrmsApiHandler.class.getMethod(
                            "handle", jakarta.servlet.http.HttpServletRequest.class);
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException(ex);
        }
        for (Map.Entry<HrmsApi, String> route : routes.published().entrySet()) {
            RequestMappingInfo info =
                    RequestMappingInfo.paths(route.getValue())
                            .methods(RequestMethod.valueOf(route.getKey().method()))
                            .options(mapping.getBuilderConfiguration())
                            .build();
            mapping.registerMapping(info, handler, target);
            log.info("HRMS API {} published at {}", route.getKey().code(), route.getValue());
        }
    }
}
