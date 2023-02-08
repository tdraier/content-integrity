package org.jahia.modules.contentintegrity.services.impl;

import org.apache.commons.lang.StringUtils;
import org.jahia.api.Constants;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;

import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.jahia.api.Constants.JAHIAMIX_LASTPUBLISHED;
import static org.jahia.api.Constants.JCR_LASTMODIFIED;
import static org.jahia.api.Constants.LASTPUBLISHED;
import static org.jahia.api.Constants.LIVE_WORKSPACE;
import static org.jahia.api.Constants.ORIGIN_WORKSPACE;

public class JCRUtils {

    private static final Logger logger = LoggerFactory.getLogger(JCRUtils.class);

    private static final String JMIX_ORIGIN_WS = "jmix:originWS";

    /**
     * Evaluates if the node is a UGC node
     *
     * @param node the node
     * @return true if the node is a UGC node
     */
    public static boolean isUGCNode(JCRNodeWrapper node) throws RepositoryException {
        if (!StringUtils.equals(node.getSession().getWorkspace().getName(), Constants.LIVE_WORKSPACE))
            throw new IllegalArgumentException("Only nodes from the live workspace can be tested");
        return node.isNodeType(JMIX_ORIGIN_WS) && node.hasProperty(ORIGIN_WORKSPACE) && LIVE_WORKSPACE.equals(node.getProperty(ORIGIN_WORKSPACE).getString());
    }

    /**
     * Evaluates if the node has some pending modifications, and so the node can be published.
     * If the node has a translation sub node in the session language, this one is not considered, the calculation is done only on the node itself.
     *
     * @param node the node
     * @return true if the node has some pending modifications
     */
    public static boolean hasPendingModifications(JCRNodeWrapper node) {
        return hasPendingModifications(node, false);
    }

    /**
     * Evaluates if the node has some pending modifications, and so the node can be published.
     * If the node has a translation sub node in the session language and if i18n has to be considered, this one is considered as well for the calculation
     *
     * @param node the node
     * @param considerI18n if true, the calculation involves the translation node
     * @return true if the node has some pending modifications
     */
    public static boolean hasPendingModifications(JCRNodeWrapper node, boolean considerI18n) {
        try {
            final Locale locale;
            if (!considerI18n
                    || (locale = node.getSession().getLocale()) == null
                    || !node.hasI18N(locale))
                return hasPendingModificationsInternal(node);

            return hasPendingModificationsInternal(node) || hasPendingModificationsInternal(node.getI18N(locale));
        } catch (RepositoryException e) {
            logger.error("", e);
            // If we can't validate that there are some pending modifications here, then we assume that there are no one.
            return false;
        }
    }

    private static boolean hasPendingModificationsInternal(Node node) {
        try {
            if (!isInDefaultWorkspace(node))
                throw new IllegalArgumentException("The publication status can be tested only in the default workspace");

            if (!node.isNodeType(JAHIAMIX_LASTPUBLISHED)) return false;
            if (node.isNodeType(Constants.JAHIAMIX_MARKED_FOR_DELETION_ROOT)) return true;
            if (!node.hasProperty(LASTPUBLISHED)) return true;
            final Calendar lastPublished = node.getProperty(LASTPUBLISHED).getDate();
            if (lastPublished == null) return true;
            if (!node.hasProperty(JCR_LASTMODIFIED)) {
                // If this occurs, then it should be detected by a dedicated integrityCheck. But here there's no way to deal with such node.
                logger.error("The node has no last modification date set " + node.getPath());
                return false;
            }
            final Calendar lastModified = node.getProperty(JCR_LASTMODIFIED).getDate();

            return lastModified.after(lastPublished);
        } catch (RepositoryException e) {
            logger.error("", e);
            // If we can't validate that there are some pending modifications here, then we assume that there are no one.
            return false;
        }
    }

    public static boolean isNeverPublished(JCRNodeWrapper node) throws RepositoryException {
        if (!isInDefaultWorkspace(node))
            throw new IllegalArgumentException("The publication status can be tested only in the default workspace");

        return nodeExists(node.getIdentifier(), getSystemSession(LIVE_WORKSPACE));
    }

    private static boolean isInWorkspace(Node node, String workspace) {
        try {
            return StringUtils.equals(node.getSession().getWorkspace().getName(), workspace);
        } catch (RepositoryException e) {
            logger.error("", e);
            return false;
        }
    }

    public static boolean isInDefaultWorkspace(Node node) {
        return isInWorkspace(node, Constants.EDIT_WORKSPACE);
    }

    public static boolean isInLiveWorkspace(Node node) {
        return isInWorkspace(node, Constants.LIVE_WORKSPACE);
    }

    public static JCRSessionWrapper getSystemSession(String workspace) {
        return getSystemSession(workspace, true);
    }

    public static JCRSessionWrapper getSystemSession(String workspace, boolean refresh) {
        try {
            final JCRSessionWrapper session = JCRSessionFactory.getInstance().getCurrentSystemSession(workspace, null, null);
            if (refresh) session.refresh(false);
            return session;
        } catch (RepositoryException e) {
            logger.error(String.format("Impossible to get the session for workspace %s", workspace), e);
            return null;
        }
    }

    public static boolean nodeExists(String uuid, JCRSessionWrapper session) {
        try {
            session.getNodeByUUID(uuid);
            return true;
        } catch (RepositoryException e) {
            return false;
        }
    }

    public static String getTranslationNodeLocale(Node translationNode) {
        try {
            if (translationNode.hasProperty(Constants.JCR_LANGUAGE))
                return translationNode.getProperty(Constants.JCR_LANGUAGE).getString();
        } catch (RepositoryException e) {
            logger.error(String.format("Impossible to read the property %s on a translation node", Constants.JCR_LANGUAGE), e);
        }
        return getTranslationNodeLocaleFromNodeName(translationNode);
    }

    public static String getTranslationNodeLocaleFromNodeName(Node translationNode) {
        try {
            return translationNode.getName().substring("j:translation_".length());
        } catch (RepositoryException e) {
            logger.error("Impossible to extract the locale", e);
            return null;
        }
    }

    public static boolean propertyValueEquals(Property p1, Property p2) throws RepositoryException {
        if (p1.isMultiple() != p2.isMultiple()) return false;
        if (p1.isMultiple()) return propertyValueEqualsMultiple(p1, p2);
        return valueEquals(p1.getValue(), p2.getValue());
    }

    private static boolean valueEquals(Value v1, Value v2) {
        if (v1 == null && v2 == null) return true;
        if (v1 == null || v2 == null) return false;

        final int type1 = v1.getType();
        if (type1 != v2.getType()) return false;

        try {
            switch (type1) {
                case PropertyType.STRING:
                case PropertyType.REFERENCE:
                case PropertyType.WEAKREFERENCE:
                case PropertyType.NAME:
                case PropertyType.PATH:
                case PropertyType.URI:
                    return StringUtils.equals(v1.getString(), v2.getString());
                case PropertyType.DATE:
                    return v1.getDate().equals(v2.getDate());
                case PropertyType.DOUBLE:
                    return v1.getDouble() == v2.getDouble();
                case PropertyType.DECIMAL:
                    return v1.getDecimal().equals(v2.getDecimal());
                case PropertyType.LONG:
                    return v1.getLong() == v2.getLong();
                case PropertyType.BOOLEAN:
                    return v1.getBoolean() == v2.getBoolean();
                // binary values are not compared
                case PropertyType.BINARY:
                default:
                    return true;
            }
        } catch (RepositoryException re) {
            logger.error("", re);
            return true;
        }
    }

    private static boolean propertyValueEqualsMultiple(Property p1, Property p2) throws RepositoryException {
        final Value[] values1 = p1.getValues();
        final Value[] values2 = p2.getValues();
        if (values1.length != values2.length) return false;

        final Map<Integer, List<Value>> valuesMap1 = Arrays.stream(values1).collect(Collectors.groupingBy(Value::getType));
        return Arrays.stream(p2.getValues()).noneMatch(v2 -> {
            final int type = v2.getType();
            if (!valuesMap1.containsKey(type)) return true;
            // TODO: this might fail with properties holding several times the same value:
            // { a, a, b } will be seen as equal to { a, b, b }
            return valuesMap1.get(type).stream().noneMatch(v1 -> valueEquals(v1, v2));
        });
    }
}