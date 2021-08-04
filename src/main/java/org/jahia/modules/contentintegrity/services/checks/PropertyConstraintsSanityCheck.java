package org.jahia.modules.contentintegrity.services.checks;

import org.apache.commons.lang.StringUtils;
import org.apache.jackrabbit.core.value.InternalValue;
import org.apache.jackrabbit.spi.commons.nodetype.constraint.ValueConstraint;
import org.jahia.api.Constants;
import org.jahia.modules.contentintegrity.api.ContentIntegrityCheck;
import org.jahia.modules.contentintegrity.api.ContentIntegrityCheckConfiguration;
import org.jahia.modules.contentintegrity.services.ContentIntegrityError;
import org.jahia.modules.contentintegrity.services.ContentIntegrityErrorList;
import org.jahia.modules.contentintegrity.services.impl.AbstractContentIntegrityCheck;
import org.jahia.modules.contentintegrity.services.impl.ContentIntegrityCheckConfigurationImpl;
import org.jahia.modules.external.ExternalNodeImpl;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.content.nodetypes.ExtendedNodeType;
import org.jahia.services.content.nodetypes.ExtendedPropertyDefinition;
import org.jahia.utils.LanguageCodeConverters;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.nodetype.PropertyDefinition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component(service = ContentIntegrityCheck.class, immediate = true, property = {
        ContentIntegrityCheck.ExecutionCondition.SKIP_ON_NT + "=" + Constants.JAHIANT_TRANSLATION
})
public class PropertyConstraintsSanityCheck extends AbstractContentIntegrityCheck implements ContentIntegrityCheck.IsConfigurable {

    private static final Logger logger = LoggerFactory.getLogger(PropertyConstraintsSanityCheck.class);

    private static final String CHECK_SITE_LANGS_ONLY_KEY = "site-langs-only";
    private static final boolean DEFAULT_CHECK_SITE_LANGS_ONLY_KEY = false;
    private static final String CHECK_PROPERTIES_WITHOUT_CONSTRAINT = "check-properties-without-constraint";
    private static final boolean DEFAULT_CHECK_PROPERTIES_WITHOUT_CONSTRAINT = false;

    private final ContentIntegrityCheckConfiguration configurations;

    public PropertyConstraintsSanityCheck() {
        configurations = new ContentIntegrityCheckConfigurationImpl();
        getConfigurations().declareDefaultParameter(CHECK_SITE_LANGS_ONLY_KEY, DEFAULT_CHECK_SITE_LANGS_ONLY_KEY, "If true, only the translation subnodes related to an active language are checked when the node is in a site");
        getConfigurations().declareDefaultParameter(CHECK_PROPERTIES_WITHOUT_CONSTRAINT, DEFAULT_CHECK_PROPERTIES_WITHOUT_CONSTRAINT, "If true, every property value will be check to validate if it is compliant with the current definition, if false only the properties with a declared constraint are checked");
    }

    @Override
    public ContentIntegrityErrorList checkIntegrityBeforeChildren(JCRNodeWrapper node) {
        final ContentIntegrityErrorList errors = createEmptyErrorsList();

        checkMandatoryProperties(node, errors);
        checkExistingProperties(node, errors);

        return errors;
    }

    private void checkMandatoryProperties(JCRNodeWrapper node, ContentIntegrityErrorList errors) {
        final Map<String, String> checkedProperties = new HashMap<>();
        doOnSupertypes(node, new SupertypeProcessor() {
            @Override
            public void execute(JCRNodeWrapper node, ExtendedNodeType extendedNodeType) throws RepositoryException {
                checkMandatoryPropertiesForType(node, extendedNodeType, checkedProperties, errors);
            }
        });
    }

    private void checkMandatoryPropertiesForType(JCRNodeWrapper node, ExtendedNodeType nodeType, Map<String, String> checkedProperties, ContentIntegrityErrorList errors) throws RepositoryException {
        final String nodeTypeName = nodeType.getName();
        final ExtendedPropertyDefinition[] extendedPropertyDefinitions = nodeType.getPropertyDefinitions();
        for (ExtendedPropertyDefinition propertyDefinition : extendedPropertyDefinitions) {
            final String propertyDefinitionName = propertyDefinition.getName();
            if (StringUtils.equals(propertyDefinitionName, "*")) continue;
            if (checkedProperties.containsKey(propertyDefinitionName)) {
                if (logger.isDebugEnabled()) {
                    logger.debug(String.format("Already encountered the property %s when checking the type %s on the node %s",
                            propertyDefinitionName, checkedProperties.get(propertyDefinitionName), node.getIdentifier()));
                }
                continue;
            }
            checkedProperties.put(propertyDefinitionName, nodeTypeName);
            if (!propertyDefinition.isMandatory()) continue;

            if (propertyDefinition.isInternationalized()) {
                doOnTranslationNodes(node, new TranslationNodeProcessor() {
                    @Override
                    public void execute(Node translationNode, String locale) throws RepositoryException {
                        checkMandatoryProperty(node, translationNode, propertyDefinitionName, propertyDefinition, locale, errors);
                    }
                });
            } else {
                checkMandatoryProperty(node, node.getRealNode(), propertyDefinitionName, propertyDefinition, null, errors);
            }
        }
    }

    private void checkMandatoryProperty(JCRNodeWrapper node, Node propertyNode, String pName,
                                        ExtendedPropertyDefinition propertyDefinition, String locale,
                                        ContentIntegrityErrorList errors) throws RepositoryException {
        if (StringUtils.equals(pName, "*")) return;
        if (!propertyDefinition.isMandatory()) return;
        if (node.getRealNode() instanceof ExternalNodeImpl) {
            final String declaringType = propertyDefinition.getDeclaringNodeType().getName();
            if (StringUtils.equals(declaringType, Constants.MIX_VERSIONABLE)) {
                return;
            }
            if (StringUtils.equals(declaringType, Constants.MIX_SIMPLEVERSIONABLE)) {
                return;
            }
        }

        if (!propertyNode.hasProperty(pName)) {
            trackMissingMandatoryValue(pName, propertyDefinition, node, locale, errors );
            return;
        }

        final Property property = propertyNode.getProperty(pName);
        if (isPropertyEmpty(property)) {
            trackMissingMandatoryValue(pName, propertyDefinition, node, locale, errors);
        }
    }

    private boolean isPropertyEmpty (Property property) throws RepositoryException {
        boolean isEmpty = true;
        if (property.isMultiple()) {
            for (Value value : property.getValues()) {
                isEmpty &= isValueEmpty(value);
            }
        } else {
            isEmpty = isValueEmpty(property.getValue());
        }
        return isEmpty;
    }

    private boolean isValueEmpty(Value value) throws RepositoryException {
        if (value == null) return true;
        if (value.getType() == PropertyType.BINARY) {
            try {
                value.getBinary();
                return false;
            } catch (RepositoryException re) {
                return true;
            }
        }
        return value.getString().length() <= 0;
    }

    private void checkExistingProperties(JCRNodeWrapper node, ContentIntegrityErrorList errors) {
        final Map<String, ExtendedPropertyDefinition> namedPropertyDefinitions = new HashMap<>();
        final Map<Integer, ExtendedPropertyDefinition> unstructuredPropertyDefinitions = new HashMap<>();
        loadPropertyDefinitions(node, namedPropertyDefinitions, unstructuredPropertyDefinitions);

        try {
            checkExistingPropertiesInternal(node.getRealNode(), null, node, namedPropertyDefinitions, unstructuredPropertyDefinitions, errors);
            doOnTranslationNodes(node, new TranslationNodeProcessor() {
                @Override
                public void execute(Node translationNode, String locale) throws RepositoryException {
                    loadPropertyDefinitions(node.getSession().getNodeByUUID(translationNode.getIdentifier()), namedPropertyDefinitions, null);
                    checkExistingPropertiesInternal(translationNode, locale, node, namedPropertyDefinitions, unstructuredPropertyDefinitions, errors);
                }
            });
        } catch (RepositoryException e) {
            logger.error("Error while checking the existing properties on the node " + node.getPath(), e);
        }
    }

    private void loadPropertyDefinitions(JCRNodeWrapper node,
                                         Map<String, ExtendedPropertyDefinition> namedPropertyDefinitions,
                                         Map<Integer, ExtendedPropertyDefinition> unstructuredPropertyDefinitions) {

        doOnSupertypes(node, true, new SupertypeProcessor() {
            @Override
            public void execute(JCRNodeWrapper node, ExtendedNodeType extendedNodeType) {
                namedPropertyDefinitions.putAll(extendedNodeType.getPropertyDefinitionsAsMap());
                if (unstructuredPropertyDefinitions == null) return;
                for (ExtendedPropertyDefinition propertyDefinition : extendedNodeType.getPropertyDefinitions()) {
                    if (StringUtils.equals(propertyDefinition.getName(), "*")) {
                        unstructuredPropertyDefinitions.put(getExtendedPropertyType(propertyDefinition), propertyDefinition);
                    }
                }
            }
        });
    }

    private void checkExistingPropertiesInternal(Node node, String locale, JCRNodeWrapper jahiaNode,
                                                 Map<String, ExtendedPropertyDefinition> namedPropertyDefinitions,
                                                 Map<Integer, ExtendedPropertyDefinition> unstructuredPropertyDefinitions,
                                                 ContentIntegrityErrorList errors) throws RepositoryException {
        if (node instanceof JCRNodeWrapper) throw new IllegalArgumentException("This method has to be executed on the real node");

        final boolean isI18n = StringUtils.isNotBlank(locale);
        final PropertyIterator properties = node.getProperties();

        while (properties.hasNext()) {
            final Property property = properties.nextProperty();
            final String pName = property.getName();

            //final boolean isWildcard = StringUtils.equals(pName, "*");
            boolean isUndeclared;
            PropertyDefinition propertyDefinition = null;
            ExtendedPropertyDefinition epd = null;
            try {
                propertyDefinition = property.getDefinition();
                if (!isI18n) isUndeclared = false;
                else if (!StringUtils.equals(propertyDefinition.getName(), "*")) isUndeclared = false;
                else {
                    isUndeclared = (!namedPropertyDefinitions.containsKey(pName) || !namedPropertyDefinitions.get(pName).isInternationalized())
                            && !unstructuredPropertyDefinitions.containsKey(getExtendedPropertyType(property, true));
                }
            } catch (RepositoryException e) {
                // if the property is declared, but its value doesn't match the declared type, then property.getDefinition()
                // raises an exception
                final ExtendedPropertyDefinition epdTmp = namedPropertyDefinitions.get(pName);
                if (namedPropertyDefinitions.containsKey(pName) && !epdTmp.isInternationalized()) {
                    epd = epdTmp;
                }
                isUndeclared = isI18n || epd == null;
            }
            if (isUndeclared) {
                trackUndeclaredProperty(pName, jahiaNode, locale, errors);
                continue;
            }

            // from here, the property is declared, propertyDefinition can be null, but in that case epd has been is not null
            if (epd == null)
                epd = getExtendedPropertyDefinition(propertyDefinition, property, isI18n, namedPropertyDefinitions, unstructuredPropertyDefinitions);
            if (epd == null) {
                logger.error(String.format("Impossible to load the property definition for the property %s on the node %s", pName, node.getPath()));
                continue;
            }

            if (isPropertyEmpty(property)) continue;

            final int propertyXType = getExtendedPropertyType(property, isI18n);
            final int definitionXType = getExtendedPropertyType(epd);
            if (propertyXType != definitionXType) {
                if (multiValuedStatusDiffer(propertyXType, definitionXType)) {
                    trackInvalidMultiValuedStatus(pName, epd, jahiaNode, locale, errors);
                }
                if (baseTypeDiffer(propertyXType, definitionXType)) {
                    trackInvalidValueType(pName, epd, getValueType(property), jahiaNode, locale, errors);
                }
                continue;
            }

            // from here, the property is declared, and matches the definition structure
            checkPropertyConstraints(property, node, locale, epd, jahiaNode, errors);
            //checkValue()
            //checkPropertyConstraints(property, epd, locale, node, jahiaNode, errors);

                /*
            else if (StringUtils.equals(propertyDefinition.getName(), "*")) {
                logger.info(propertyDefinition.getClass().toString());
                ExtendedPropertyDefinition extendedPropertyDefinition = null;
                for (ExtendedPropertyDefinition definition : node.getPrimaryNodeType().getPropertyDefinitions()) {
                    if (StringUtils.equals(definition.getName(), pName)) {  // TODO KO
                        extendedPropertyDefinition = definition;
                        break;
                    }
                }
                if (extendedPropertyDefinition == null) {
                    for (ExtendedNodeType mixinNodeType : node.getMixinNodeTypes()) {
                        for (ExtendedPropertyDefinition definition : mixinNodeType.getPropertyDefinitions()) {
                            if (StringUtils.equals(definition.getName(), pName)) {
                                extendedPropertyDefinition = definition;
                                break;
                            }
                        }
                        if (extendedPropertyDefinition != null) break;
                    }
                }

                if (extendedPropertyDefinition != null) {
                    checkProperty(node, checkedNode, pName, extendedPropertyDefinition, locale, errors);
                } else {
                    logger.error(String.format("Impossible to check the property %s on the node %s "));
                }
            } else {
                logger.error(String.format("Unexpected property %s on the node %s, only properties declared under the name * should be matched here", pName, node.getPath()));
            }
                 */
        }
    }

    private void checkPropertyConstraints(Property property, Node node, String locale, ExtendedPropertyDefinition epd, JCRNodeWrapper jahiaNode, ContentIntegrityErrorList errors) throws RepositoryException {
        final boolean hasConstraints = epd.getValueConstraints() != null && epd.getValueConstraints().length > 0;
        if (!hasConstraints) return;

        final String pName = property.getName();
        if (epd.isMultiple()) {
            int idx = 0;
            for (Value value : property.getValues()) {
                checkValue(value, idx, pName, epd, jahiaNode, locale, errors);
                idx++;
            }
        } else {
            checkValue(property.getValue(), -1, pName, epd, jahiaNode, locale, errors);
        }
    }

    private void checkValue(Value value, int valueIdx,
                               String pName, ExtendedPropertyDefinition epd,
                               JCRNodeWrapper jahiaNode, String locale,
                               ContentIntegrityErrorList errors) throws RepositoryException {
        if (isValueEmpty(value)) return;
        final String valueStr = value.getType() == PropertyType.BINARY ? "<binary>" : value.getString();
        if (!constraintIsValid(value, epd)) {
            trackInvalidValueConstraint(pName, epd, valueStr, valueIdx, jahiaNode, locale, epd.getValueConstraints(), errors);
        }
    }

    private boolean constraintIsValid(Value value, ExtendedPropertyDefinition propertyDefinition) {
        final ValueConstraint[] constraints = propertyDefinition.getValueConstraintObjects();
        if (constraints == null || constraints.length == 0) {
            // no constraints to check
            return true;
        }

        for (ValueConstraint constraint : constraints) {
            try {
                constraint.check(InternalValue.create(value, null, null));
                return true;
            } catch (ConstraintViolationException ignored) {
            } catch (RepositoryException e) {
                logger.error("", e);
            }
        }

        return false;
    }

    private int getValueType(Property property) throws RepositoryException {
        if (property.isMultiple()) {
            final Set<Integer> types = Arrays.stream(property.getValues())
                    .map(Value::getType)
                    .collect(Collectors.toSet());
            if (types.size() == 1) return types.iterator().next();
        } else {
            if (property.getValue() != null) return property.getValue().getType();
        }
        return 0;
    }

    private int getExtendedPropertyType(Property property, boolean isI18n) {
        boolean isMultiple = false;
        try {
            isMultiple = property.isMultiple();
            return getExtendedPropertyType(getValueType(property), isI18n, isMultiple);
        } catch (RepositoryException e) {
            logger.error("", e);
            return getExtendedPropertyType(0, isI18n, isMultiple);
        }
    }

    private int getExtendedPropertyType(ExtendedPropertyDefinition epd) {
        return getExtendedPropertyType(epd.getRequiredType(), epd.isInternationalized(), epd.isMultiple());
    }

    private int getExtendedPropertyType(int type, boolean isI18n, boolean isMultiple) {
        int xType = type;
        if (isI18n) xType += 100;
        if (isMultiple) xType += 1000;
        return xType;
    }

    private boolean baseTypeDiffer(int propertyXType, int definitionXType) {
        final int definitionBaseType = definitionXType % 100;
        final int propertyBaseType = propertyXType % 100;
        if (propertyBaseType == PropertyType.WEAKREFERENCE && definitionBaseType == PropertyType.REFERENCE) return false;
        return definitionBaseType != PropertyType.UNDEFINED && propertyBaseType != definitionBaseType;
    }

    private boolean multiValuedStatusDiffer(int propertyXType, int definitionXType) {
        return Math.floor(propertyXType / 1000d) != Math.floor(definitionXType / 1000d);
    }

    private ExtendedPropertyDefinition getExtendedPropertyDefinition(PropertyDefinition propertyDefinition,
                                                                     Property property, boolean isI18n,
                                                                     Map<String, ExtendedPropertyDefinition> namedPropertyDefinitions,
                                                                     Map<Integer, ExtendedPropertyDefinition> unstructuredPropertyDefinitions) throws RepositoryException {
        final String propertyDefinitionName = propertyDefinition.getName();
        if (StringUtils.equals(propertyDefinitionName, "*")) {
            if (isI18n && StringUtils.equals(propertyDefinition.getDeclaringNodeType().getName(), Constants.JAHIANT_TRANSLATION)) {
                final ExtendedPropertyDefinition epd = namedPropertyDefinitions.get(property.getName());
                if (epd != null && epd.isInternationalized()) return epd;
            }
            return unstructuredPropertyDefinitions.get(getExtendedPropertyType(property, isI18n));
        } else {
            return namedPropertyDefinitions.get(propertyDefinitionName);
        }
    }

    private void trackMissingMandatoryValue(String propertyName, ExtendedPropertyDefinition propertyDefinition,
                                            JCRNodeWrapper node, String locale,
                                            ContentIntegrityErrorList errors) {
        trackError(ErrorType.EMPTY_MANDATORY_PROPERTY, propertyName, propertyDefinition, null, -1, -1, node, locale, null, errors);
    }

    private void trackInvalidValueConstraint(String propertyName, ExtendedPropertyDefinition propertyDefinition,
                                             String value, int valueIdx,
                                             JCRNodeWrapper node, String locale,
                                             String[] valueConstraints, ContentIntegrityErrorList errors) {
        final HashMap<String, Object> customExtraInfos = new HashMap<>();
        customExtraInfos.put("constraints", Arrays.toString(valueConstraints));
        trackError(ErrorType.INVALID_VALUE_CONSTRAINT, propertyName, propertyDefinition, value, valueIdx, -1, node, locale, customExtraInfos, errors);
    }

    private void trackInvalidValueType(String propertyName, ExtendedPropertyDefinition propertyDefinition,
                                       int valueType,
                                       JCRNodeWrapper node, String locale,
                                       ContentIntegrityErrorList errors) {
        trackError(ErrorType.INVALID_VALUE_TYPE, propertyName, propertyDefinition, null, -1, valueType, node, locale, null, errors);
    }

    private void trackInvalidMultiValuedStatus(String propertyName, ExtendedPropertyDefinition epd,
                                               JCRNodeWrapper node, String locale,
                                               ContentIntegrityErrorList errors) {
        trackError(ErrorType.INVALID_MULTI_VALUED_STATUS, propertyName, epd, null, -1, -1, node, locale, null, errors);
    }

    private void trackUndeclaredProperty(String propertyName,
                                         JCRNodeWrapper node, String locale,
                                         ContentIntegrityErrorList errors) {
        trackError(ErrorType.UNDECLARED_PROPERTY, propertyName, null, null, -1, -1, node, locale, null, errors);
    }

    private void trackError(ErrorType errorType,
                            String propertyName, ExtendedPropertyDefinition propertyDefinition,
                            String value, int valueIdx, int valueType,
                            JCRNodeWrapper node, String locale,
                            Map<String, Object> customExtraInfos, ContentIntegrityErrorList errors) {
        final ContentIntegrityError error = createError(node, locale, errorType.desc)
                .addExtraInfo("error-type", errorType)
                .addExtraInfo("property-name", propertyName);
        if (propertyDefinition != null) {
            error.addExtraInfo("declaring-type", propertyDefinition.getDeclaringNodeType().getName());
        }
        if (value != null) {
            error.addExtraInfo("invalid-value", value);
            if (propertyDefinition != null && propertyDefinition.isMultiple())
                error.addExtraInfo("value-index", valueIdx);
        }
        if (valueType >= 0) {
            error.addExtraInfo("value-type", PropertyType.nameFromValue(valueType));
            if (propertyDefinition != null)
                error.addExtraInfo("expected-value-type", PropertyType.nameFromValue(propertyDefinition.getRequiredType()));
        }
        if (customExtraInfos != null) {
            customExtraInfos.forEach(error::addExtraInfo);
        }

        errors.addError(error);
    }

    private boolean checkSiteLangsOnly() {
        final Object o = getConfigurations().getParameter(CHECK_SITE_LANGS_ONLY_KEY);
        if (o instanceof Boolean) return (boolean) o;
        if (o instanceof String) return Boolean.parseBoolean((String) o);
        return DEFAULT_CHECK_SITE_LANGS_ONLY_KEY;
    }

    private void doOnTranslationNodes(JCRNodeWrapper node, TranslationNodeProcessor translationNodeProcessor) throws RepositoryException {
        if (checkSiteLangsOnly() && StringUtils.startsWith(node.getPath(), "/sites/")) {
            final JCRSiteNode site = node.getResolveSite();
            final List<Locale> locales = node.getSession().getWorkspace().getName().equals(Constants.EDIT_WORKSPACE) ?
                    site.getLanguagesAsLocales() : site.getActiveLiveLanguagesAsLocales();
            for (Locale locale : locales) {
                final Node translationNode;
                try {
                    translationNode = node.getI18N(locale, false);
                } catch (ItemNotFoundException infe) {
                    continue;
                }
                translationNodeProcessor.execute(translationNode, LanguageCodeConverters.localeToLanguageTag(locale));
            }
        } else {
            final NodeIterator translationNodesIterator = node.getI18Ns();
            while (translationNodesIterator.hasNext()) {
                final Node translationNode = translationNodesIterator.nextNode();
                final String locale = getTranslationNodeLocale(translationNode);
                if (StringUtils.isBlank(locale)) {
                    logger.error(String.format("Skipping a translation node since its language is invalid: %s", translationNode.getIdentifier()));
                    continue;
                }
                translationNodeProcessor.execute(translationNode, locale);
            }
        }
    }

    private abstract static class TranslationNodeProcessor {

        public abstract void execute(Node translationNode, String locale) throws RepositoryException;
    }

    private void doOnSupertypes(JCRNodeWrapper node, SupertypeProcessor supertypeProcessor) {
        doOnSupertypes(node, false, supertypeProcessor);
    }

    private void doOnSupertypes(JCRNodeWrapper node, boolean reverseOrder, SupertypeProcessor supertypeProcessor) {
        final ExtendedNodeType primaryNodeType;
        final ExtendedNodeType[] mixinNodeTypes;
        try {
            primaryNodeType = node.getPrimaryNodeType();
            mixinNodeTypes = node.getMixinNodeTypes();
        } catch (RepositoryException e) {
            logger.error("Impossible to load the types of the node", e);
            return;
        }

        final List<ExtendedNodeType> superTypes = new ArrayList<>(mixinNodeTypes.length + 1);
        superTypes.add(primaryNodeType);
        superTypes.addAll(Arrays.asList(mixinNodeTypes));
        if (reverseOrder) Collections.reverse(superTypes);

        String identifier = null;
        try {
            identifier = node.getIdentifier();
        } catch (RepositoryException e) {
            logger.error("", e);
        }

        try {
            for (ExtendedNodeType superType : superTypes) {
                supertypeProcessor.execute(node, superType);
            }
        } catch (RepositoryException e) {
            logger.error("Error while checking the node " + identifier, e);
        }
    }

    private abstract static class SupertypeProcessor {

        public abstract void execute(JCRNodeWrapper node, ExtendedNodeType extendedNodeType) throws RepositoryException;
    }

    @Override
    public ContentIntegrityCheckConfiguration getConfigurations() {
        return configurations;
    }

    private enum ErrorType {
        EMPTY_MANDATORY_PROPERTY("Missing mandatory property"),
        INVALID_VALUE_TYPE("The value does not match the type declared in the property definition"),
        INVALID_MULTI_VALUED_STATUS("The single/multi valued status differs between the value and the definition"),
        INVALID_VALUE_CONSTRAINT("The value does not match the constraint declared in the property definition"),
        UNDECLARED_PROPERTY("Undeclared property");

        private final String desc;

        ErrorType(String desc) {
            this.desc = desc;
        }
    }
}