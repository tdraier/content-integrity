package org.jahia.modules.contentintegrity.services.checks;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.jahia.api.Constants;
import org.jahia.modules.contentintegrity.api.ContentIntegrityCheck;
import org.jahia.modules.contentintegrity.api.ContentIntegrityCheckConfiguration;
import org.jahia.modules.contentintegrity.services.ContentIntegrityError;
import org.jahia.modules.contentintegrity.services.ContentIntegrityErrorList;
import org.jahia.modules.contentintegrity.services.impl.AbstractContentIntegrityCheck;
import org.jahia.modules.contentintegrity.services.impl.ContentIntegrityCheckConfigurationImpl;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.nodetypes.ExtendedNodeType;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.ItemNotFoundException;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.jahia.api.Constants.EDIT_WORKSPACE;
import static org.jahia.api.Constants.LIVE_WORKSPACE;
import static org.jahia.api.Constants.ORIGIN_WORKSPACE;
import static org.jahia.modules.contentintegrity.services.impl.ContentIntegrityCheckConfigurationImpl.BOOLEAN_PARSER;

@Component(service = ContentIntegrityCheck.class, immediate = true, property = {
        ContentIntegrityCheck.ExecutionCondition.APPLY_ON_WS + "=" + Constants.LIVE_WORKSPACE
})
public class PublicationSanityLiveCheck extends AbstractContentIntegrityCheck implements ContentIntegrityCheck.IsConfigurable, ContentIntegrityCheck.SupportsIntegrityErrorFix {

    private static final Logger logger = LoggerFactory.getLogger(PublicationSanityLiveCheck.class);

    private static final String DEEP_COMPARE_PUBLISHED_NODES = "deep-compare-published-nodes";
    private static final String JMIX_LIVE_PROPERTIES = "jmix:liveProperties";
    private static final String J_LIVE_PROPERTIES = "j:liveProperties";
    private static final String JMIX_ORIGIN_WS = "jmix:originWS";
    /*
    Lock related properties are ignored because they are set only in the default WS, and do not alter the publication status
     */
    private static final List<String> IGNORED_DEFAULT_ONLY_PROPS = Arrays.asList("jcr:lockOwner", "j:lockTypes", "j:locktoken", "jcr:lockIsDeep");
    /*
    J_LIVE_PROPERTIES is set on the node only in the live WS to keep track of the UGC properties
    NODENAME is sometimes missing in the default WS. Since it reflects the node name, let's ignore it
     */
    private static final List<String> IGNORED_LIVE_ONLY_PROPS = Arrays.asList(J_LIVE_PROPERTIES, Constants.NODENAME);
    /*
    JCR_LASTMODIFIED is not compared, because it can be updated in live when writing UCG properties
    Versioning related properties are not compared because each workspace works with its own graph
    JCR_MIXINTYPES might differ if some mixins are added in live. Mixins are compared separately, without using this property
     */
    private static final List<String> NOT_COMPARED_PROPERTIES = Arrays.asList(Constants.JCR_LASTMODIFIED,
            Constants.JCR_BASEVERSION, Constants.JCR_PREDECESSORS,
            Constants.JCR_MIXINTYPES);

    private final ContentIntegrityCheckConfiguration configurations;

    private enum ErrorType {NO_DEFAULT_NODE, MISSING_PROP_LIVE, MISSING_PROP_DEFAULT, DIFFERENT_PROP_VAL, DIFFERENT_MIXINS}

    public PublicationSanityLiveCheck() {
        configurations = new ContentIntegrityCheckConfigurationImpl();
        configurations.declareDefaultParameter(DEEP_COMPARE_PUBLISHED_NODES, false, BOOLEAN_PARSER, "If true, the value of every property will be compared between default and live on the nodes without pending modification");
        // TODO: for multi-value properties, compare the order of the values
    }

    @Override
    public ContentIntegrityCheckConfiguration getConfigurations() {
        return configurations;
    }

    private boolean isDeepComparePublishedNodes() {
        return (boolean) configurations.getParameter(DEEP_COMPARE_PUBLISHED_NODES);
    }

    @Override
    public ContentIntegrityErrorList checkIntegrityBeforeChildren(JCRNodeWrapper node) {
        try {
            final JCRSessionWrapper defaultSession = getSystemSession(EDIT_WORKSPACE, true);
            if (node.isNodeType(JMIX_ORIGIN_WS) && node.hasProperty(ORIGIN_WORKSPACE) && LIVE_WORKSPACE.equals(node.getProperty(ORIGIN_WORKSPACE).getString())) {
                // UGC
                // TODO: check if there's a node with the same ID in default
                return null;
            }
            final JCRNodeWrapper defaultNode;
            try {
                defaultNode = defaultSession.getNodeByIdentifier(node.getIdentifier());
            } catch (ItemNotFoundException infe) {
                if (node.isNodeType(Constants.JAHIANT_TRANSLATION)) {
                    /*
                    When a node has no i18n property in default, and an i18n property is written in live,
                    the translation node is created in live, but it has no j:originWS property
                    */
                    return null;
                }

                final String msg = "Found not-UGC node which exists only in live";
                final ContentIntegrityError error = createError(node, msg)
                        .setErrorType(ErrorType.NO_DEFAULT_NODE);
                return createSingleError(error);
            }
            final ContentIntegrityErrorList errors = createEmptyErrorsList();
            deepComparePublishedNodes(defaultNode, node, errors);
            return errors;
        } catch (RepositoryException e) {
            logger.error("", e);
            return null;
        }
    }

    private void deepComparePublishedNodes(JCRNodeWrapper defaultNode, JCRNodeWrapper liveNode, ContentIntegrityErrorList errors) {
        if (!isDeepComparePublishedNodes()) return;

        /*
        The repository root is not auto published.
        When ACL are defined on the repository root, jmix:accessControlled is added to the node in default , but not in live,
        what makes differences on the property jcr:mixinTypes
        */
        if (StringUtils.equals(defaultNode.getPath(), "/")) return;

        if (hasPendingModifications(defaultNode)) return;

        try {
            final PropertyIterator properties = defaultNode.getRealNode().getProperties();
            while (properties.hasNext()) {
                final Property defaultProperty = properties.nextProperty();
                final String pName = defaultProperty.getName();
                if (!liveNode.getRealNode().hasProperty(pName)) {
                    if (!IGNORED_DEFAULT_ONLY_PROPS.contains(pName)) {
                        errors.addError(createError(liveNode, "Missing property in live on a published node")
                                .setErrorType(ErrorType.MISSING_PROP_LIVE)
                                .addExtraInfo("property name", pName));
                    }
                } else if (!NOT_COMPARED_PROPERTIES.contains(pName) &&
                        !propertyValueEquals(defaultProperty, liveNode.getRealNode().getProperty(pName))) {
                    errors.addError(createError(liveNode, "Different value for a property in default and live on a published node")
                            .setErrorType(ErrorType.DIFFERENT_PROP_VAL)
                            .addExtraInfo("property name", pName));
                }
            }
            final PropertyIterator liveProperties = liveNode.getRealNode().getProperties();
            Set<String> ugcProperties = null;
            while (liveProperties.hasNext()) {
                final Property liveProperty = liveProperties.nextProperty();
                final String pName = liveProperty.getName();
                if (!IGNORED_LIVE_ONLY_PROPS.contains(pName) && !defaultNode.getRealNode().hasProperty(pName)) {
                    if (ugcProperties == null) {
                        ugcProperties = getUgcProperties(liveNode);
                    }
                    if (ugcProperties == null || !ugcProperties.contains(pName)) {
                        errors.addError(createError(liveNode, "Missing property in default on a published node")
                                .setErrorType(ErrorType.MISSING_PROP_DEFAULT)
                                .addExtraInfo("property name", pName));
                    }
                }
            }

            compareMixins(defaultNode, liveNode, errors);

        } catch (RepositoryException e) {
            logger.error("", e);
        }
    }

    private Set<String> getUgcProperties(JCRNodeWrapper liveNode) throws RepositoryException {
        if (!liveNode.hasProperty(J_LIVE_PROPERTIES)) return null;

        return Arrays.stream(liveNode.getProperty(J_LIVE_PROPERTIES).getValues()).map(value -> {
            try {
                return value.getString();
            } catch (RepositoryException e) {
                logger.error("", e);
                return null;
            }
        }).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private void compareMixins(JCRNodeWrapper defaultNode, JCRNodeWrapper liveNode, ContentIntegrityErrorList errors) {
        try {
            final Set<String> defaultMixins = getNodeMixins(defaultNode);
            final Set<String> liveMixins = getNodeMixins(liveNode);
            final Collection<String> liveOnlyMixins = CollectionUtils.subtract(liveMixins, defaultMixins);

            if (CollectionUtils.isEmpty(liveOnlyMixins)) {
                if (defaultMixins.size() == liveMixins.size()) {
                    // No mixin is only in live, and the collections have the same size, so they are equal
                    return;
                } else {
                    // In this case, there are some additional mixins in default
                    errors.addError(createError(liveNode, "Different mixins on a published node")
                            .setErrorType(ErrorType.DIFFERENT_MIXINS));
                }
                return;
            }

            Set<String> ugcMixins = null;
            if (liveNode.isNodeType(JMIX_LIVE_PROPERTIES) && liveNode.hasProperty(J_LIVE_PROPERTIES)) {
                ugcMixins = Arrays.stream(liveNode.getProperty(J_LIVE_PROPERTIES).getValues())
                        .map(this::getStringValue)
                        .filter(v -> StringUtils.startsWith(v, "jcr:mixinTypes="))
                        .map(v -> StringUtils.substring(v, "jcr:mixinTypes=".length()))
                        .collect(Collectors.toSet());
            }
            final Set<String> finalUgcMixins = ugcMixins;
            if (liveOnlyMixins.stream().anyMatch(s -> {
                if (StringUtils.equals(s, JMIX_LIVE_PROPERTIES)) return false;
                return finalUgcMixins == null || !finalUgcMixins.contains(s);
            })) {
                errors.addError(createError(liveNode, "Different mixins on a published node")
                        .setErrorType(ErrorType.DIFFERENT_MIXINS));
            }
        } catch (RepositoryException e) {
            logger.error("", e);
        }
    }

    private String getStringValue(Value v) {
        try {
            return v.getString();
        } catch (RepositoryException e) {
            logger.error("", e);
            return null;
        }
    }

    private Set<String> getNodeMixins(JCRNodeWrapper node) throws RepositoryException {
        return Arrays.stream(node.getMixinNodeTypes()).map(ExtendedNodeType::getName).collect(Collectors.toSet());
    }

    @Override
    public boolean fixError(JCRNodeWrapper node, ContentIntegrityError integrityError) throws RepositoryException {
        final Object errorTypeObject = integrityError.getErrorType();
        if (!(errorTypeObject instanceof ErrorType)) {
            logger.error("Unexpected error type: " + errorTypeObject);
            return false;
        }
        final ErrorType errorType = (ErrorType) errorTypeObject;
        switch (errorType) {
            case NO_DEFAULT_NODE:
                // We assume here that the deletion has not been correctly published. An alternative fix would be to consider
                // that this node is not correctly flagged as UGC, and so to flag it as such.
                node.remove();
                node.getSession().save();
                return true;
        }
        return false;
    }
}
