package org.jahia.modules.contentintegrity.services.checks;

import org.apache.commons.lang.StringUtils;
import org.jahia.api.Constants;
import org.jahia.modules.contentintegrity.api.ContentIntegrityCheck;
import org.jahia.modules.contentintegrity.api.ContentIntegrityErrorList;
import org.jahia.modules.contentintegrity.services.impl.AbstractContentIntegrityCheck;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.sites.JahiaSitesService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Component(service = ContentIntegrityCheck.class, immediate = true, property = {
        ContentIntegrityCheck.ENABLED + ":Boolean=false"
})
public class StaticInternalLinksCheck extends AbstractContentIntegrityCheck {

    private static final Logger logger = LoggerFactory.getLogger(StaticInternalLinksCheck.class);

    private static final int TEXT_EXTRACT_MAX_LENGTH = 200;
    public static final int TEXT_EXTRACT_READABILITY_ZONE_LENGTH = 20;

    private final Set<String> domains = new HashSet<>();
    private final Map<String, Collection<String>> ignoredProperties = new HashMap<>();

    @Override
    protected void activateInternal(ComponentContext context) {
        ignoredProperties.put(Constants.JAHIANT_VIRTUALSITE, new HashSet<>());
        ignoredProperties.get(Constants.JAHIANT_VIRTUALSITE).add("j:serverName");
        ignoredProperties.get(Constants.JAHIANT_VIRTUALSITE).add("j:serverNameAliases");
    }

    @Override
    protected void initializeIntegrityTestInternal(JCRNodeWrapper scanRootNode, Collection<String> excludedPaths) {
        try {
            final JCRSessionWrapper systemSession = JCRSessionFactory.getInstance().getCurrentSystemSession(Constants.LIVE_WORKSPACE, null, null);
            JahiaSitesService.getInstance().getSitesNodeList(systemSession).stream()
                    .map(JCRSiteNode::getAllServerNames)
                    .forEach(domains::addAll);
        } catch (RepositoryException e) {
            logger.error("", e);
        }
    }

    @Override
    public ContentIntegrityErrorList checkIntegrityBeforeChildren(JCRNodeWrapper node) {
        final PropertyIterator properties;
        try {
            properties = node.getProperties();
        } catch (RepositoryException e) {
            logger.error("", e);
            return null;
        }
        final ContentIntegrityErrorList errors = createEmptyErrorsList();
        while (properties.hasNext()) {
            final Property property = properties.nextProperty();
            try {
                if (property.isMultiple()) {
                    Arrays.stream(property.getValues())
                            .forEach(value -> checkValue(value, errors, node, property));
                } else {
                    checkValue(property.getValue(), errors, node, property);
                }
            } catch (RepositoryException e) {
                logger.error("", e);
            }
        }
        return errors;
    }

    private void checkValue(Value value, ContentIntegrityErrorList errors, JCRNodeWrapper node, Property property) {
        if (value.getType() != PropertyType.STRING) return;
        final String propertyName;
        String tmpPropName = null;
        try {
            tmpPropName = property.getName();
            // For performance purpose, we just check the exact PT, without considering the inheritance or the mixins
            // TODO : for the moment, it would not work with ignored i18n properties
            final String pt = node.getPrimaryNodeTypeName();
            if (ignoredProperties.containsKey(pt) && ignoredProperties.get(pt).contains(tmpPropName))
                return;
        } catch (RepositoryException e) {
            logger.error("", e);
        }
        propertyName = StringUtils.defaultString(tmpPropName, "<failed to calculate>");
        try {
            final String text = value.getString();
            domains.forEach(domain -> {
                if (StringUtils.contains(text, domain)) {
                    errors.addError(createError(node, "Hardcoded site domain in a String value")
                            .addExtraInfo("property-name", propertyName)
                            .addExtraInfo("property-value", getTextExtract(text, domain), true)
                            .addExtraInfo("domain", domain));
                }
            });
        } catch (RepositoryException e) {
            logger.error("", e);
        }
    }

    private String getTextExtract(String text, String domain) {
        if (text.length() <= TEXT_EXTRACT_MAX_LENGTH) return text;
        final int offset = StringUtils.indexOf(text, domain) - TEXT_EXTRACT_READABILITY_ZONE_LENGTH;
        return StringUtils.abbreviate(text, offset, TEXT_EXTRACT_MAX_LENGTH);
    }
}